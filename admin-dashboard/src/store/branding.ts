import { create } from "zustand";
import { apiGet, apiPost, apiDelete } from "@/api/client";
import api from "@/api/client";

/**
 * Branding store — keeps the admin-uploaded logos in memory so the whole
 * app shares one fetch.
 *
 * Shape: { brand, aba, wing, acleda, bakong } — each is a `data:` URL or null.
 *
 * The dashboard fetches once at app mount via {@link useBrandingBootstrap}.
 * Admin page can call {@link refreshBranding} to bypass the Spring + bridge
 * caches.
 */

export type LogoSlot = "brand" | "aba" | "wing" | "acleda" | "bakong";

interface BrandingState {
  loaded: boolean;
  loading: boolean;
  error: string | null;
  logos: Partial<Record<LogoSlot, string>>;
  meta: Record<string, unknown> | null;

  load: () => Promise<void>;
  refresh: () => Promise<void>;
  refreshPublic: () => Promise<void>;
  upsert: (slot: LogoSlot, dataUrl: string) => Promise<void>;
  remove: (slot: LogoSlot) => Promise<void>;
  setLogos: (logos: Partial<Record<LogoSlot, string>>, meta?: Record<string, unknown> | null) => void;
}

export const useBrandingStore = create<BrandingState>((set, get) => ({
  loaded: false,
  loading: false,
  error: null,
  logos: {},
  meta: null,

  /** First fetch — no-op if already loaded. */
  async load() {
    if (get().loading || get().loaded) return;
    set({ loading: true, error: null });
    try {
      const logos = await apiGet<Partial<Record<LogoSlot, string>>>(
        "/api/v1/public/branding",
      );
      set({ logos: logos ?? {}, loaded: true, loading: false });
    } catch (e) {
      set({
        loaded: true,           // Mark loaded so we fall back to defaults.
        loading: false,
        error: (e as { message?: string }).message ?? "Could not load branding",
      });
    }
  },

  /**
   * Public refresh — bypasses Spring's 60s in-memory cache AND the bridge's
   * 60s cache by passing ?refresh=true all the way through. Available to
   * any signed-in or signed-out caller (the public-branding endpoint is
   * whitelisted in SecurityConfig). Used by the layout's
   * "tab regained focus" hook so a user who just changed their logo on
   * the Telegram bot sees the new one within ~1 second of switching back
   * to the dashboard tab.
   */
  async refreshPublic() {
    set({ loading: true, error: null });
    try {
      const logos = await apiGet<Partial<Record<LogoSlot, string>>>(
        "/api/v1/public/branding?refresh=true",
      );
      set({ logos: logos ?? {}, loaded: true, loading: false });
    } catch (e) {
      set({
        loading: false,
        error: (e as { message?: string }).message ?? "Refresh failed",
      });
    }
  },

  /** Admin-only — force-refresh from the bot through Spring. */
  async refresh() {
    set({ loading: true, error: null });
    try {
      const r = await apiPost<{
        logos: Partial<Record<LogoSlot, string>>;
        meta: Record<string, unknown>;
      }>("/api/v1/admin/branding/refresh");
      set({ logos: r.logos ?? {}, meta: r.meta, loaded: true, loading: false });
    } catch (e) {
      set({
        loading: false,
        error: (e as { message?: string }).message ?? "Refresh failed",
      });
      throw e;
    }
  },

  /** Admin-only — upload a logo for a slot (sent as a base64 data: URL). */
  async upsert(slot, dataUrl) {
    set({ loading: true, error: null });
    try {
      const r = await api.put<{
        success: boolean;
        data: {
          logos: Partial<Record<LogoSlot, string>>;
          meta: Record<string, unknown>;
        };
      }>(`/api/v1/admin/branding/${slot}`, { dataUrl });
      const data = r.data.data;
      set({ logos: data.logos ?? {}, meta: data.meta, loaded: true, loading: false });
    } catch (e) {
      set({
        loading: false,
        error: (e as { message?: string }).message ?? "Upload failed",
      });
      throw e;
    }
  },

  /** Admin-only — remove a logo. */
  async remove(slot) {
    set({ loading: true, error: null });
    try {
      await apiDelete(`/api/v1/admin/branding/${slot}`);
      // Spring returns 204 for DELETE, so we re-read.
      const logos = await apiGet<Partial<Record<LogoSlot, string>>>(
        "/api/v1/public/branding",
      );
      set({ logos: logos ?? {}, loading: false });
    } catch (e) {
      set({
        loading: false,
        error: (e as { message?: string }).message ?? "Delete failed",
      });
      throw e;
    }
  },

  setLogos(logos, meta) {
    set({ logos, meta: meta ?? null, loaded: true });
  },
}));

/** Convenience hook for components that just need a single slot. */
export function useLogo(slot: LogoSlot): string | undefined {
  return useBrandingStore((s) => s.logos[slot]);
}
