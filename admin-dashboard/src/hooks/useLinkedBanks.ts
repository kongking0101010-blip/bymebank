import { useQuery } from "@tanstack/react-query";

import { apiGet, ApiError } from "@/api/client";
import type { BankType } from "@/components/BankBadge";

/* ────────────────────────────────────────────────────────────── */
/*  Single source of truth for "which banks does this sk_ key     */
/*  actually have registered upstream?".                          */
/*                                                                */
/*  Backend route:                                                */
/*    GET /api/v1/me/upstream-key/{keyId}/banks                   */
/*  Returns:                                                      */
/*    { keyId, valid, registeredBanks: ["aba", ...],              */
/*      merchantName, reason? }                                   */
/*                                                                */
/*  - `valid: false` + reason="not_found"|"format"  → bad key     */
/*  - `valid: true`  + registeredBanks=[]            → no banks   */
/*  - `valid: true`  + registeredBanks=["aba",…]     → render     */
/* ────────────────────────────────────────────────────────────── */

const ALL_SLOTS = ["aba", "wing", "acleda", "bakong"] as const;
type Slot = (typeof ALL_SLOTS)[number];

interface BanksApiResponse {
  keyId: string;
  valid: boolean;
  registeredBanks?: string[] | null;
  merchantName?: string | null;
  reason?: string | null;
}

export interface LinkedBanksResult {
  /** True while the request is in-flight (initial load or refetch). */
  loading: boolean;
  /** Did upstream confirm the key exists and is registered. */
  valid: boolean;
  /** The banks the user has actually linked. EXACT — do not pad. */
  banks: BankType[];
  /** Display name returned by upstream for the key. */
  merchantName: string;
  /** Why the key is invalid: "not_found" | "format" | "no_banks_linked" | undefined. */
  reason?: string;
  /** Network/server error if the call itself failed. */
  error?: ApiError | null;
  /** Manual refetch — useful after the user just linked a new bank. */
  refetch: () => void;
}

/**
 * `keyId` is the dashboard's internal `UserApiKey.id` (UUID), NOT the raw sk_.
 * Spring scopes the lookup to the authenticated user, so we never expose
 * raw sk_ values in URLs.
 *
 * Pass `null` when you don't have a key yet — the hook stays in a
 * deterministic "loading: false, valid: false, banks: []" idle state.
 */
export function useLinkedBanks(keyId: string | null | undefined): LinkedBanksResult {
  const q = useQuery<BanksApiResponse, ApiError>({
    enabled: !!keyId,
    queryKey: ["upstream-key", "banks", keyId ?? "none"],
    queryFn: () => apiGet(`/api/v1/me/upstream-key/${keyId}/banks`),
    // 30 s matches the §1 server-side cache window in the spec.
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    // Live keys flip linked-bank state rarely; bursts of retries are noise.
    retry: 1,
  });

  if (!keyId) {
    return {
      loading: false,
      valid: false,
      banks: [],
      merchantName: "",
      reason: undefined,
      error: null,
      refetch: () => q.refetch(),
    };
  }

  const data = q.data;
  const slugs: Slot[] = (data?.registeredBanks ?? [])
      .map((s) => (s ?? "").toLowerCase())
      .filter((s): s is Slot => (ALL_SLOTS as readonly string[]).includes(s));

  // De-dupe while preserving upstream order.
  const uniqueSlugs = Array.from(new Set(slugs));
  const banks: BankType[] = uniqueSlugs.map((s) => s.toUpperCase() as BankType);

  return {
    loading: q.isLoading || q.isFetching,
    valid: !!data?.valid,
    banks,
    merchantName: data?.merchantName ?? "",
    reason: data?.reason ?? undefined,
    error: q.error ?? null,
    refetch: () => q.refetch(),
  };
}

/** Human label for a bank slug — used in summary lines and banners. */
export const BANK_LABELS: Record<BankType, string> = {
  ABA: "ABA Bank",
  WING: "Wing Bank",
  ACLEDA: "ACLEDA Bank",
  BAKONG: "Bakong KHQR",
};

/**
 * Builds the live "X payment methods linked: A, B, C" line. NEVER hardcode
 * the count or names — always derive from the linked list.
 */
export function summariseLinkedBanks(banks: BankType[]): string {
  if (banks.length === 0) return "No payment methods linked";
  const names = banks.map((b) => BANK_LABELS[b]).join(", ");
  const noun = banks.length === 1 ? "method" : "methods";
  return `${banks.length} payment ${noun} linked: ${names}`;
}
