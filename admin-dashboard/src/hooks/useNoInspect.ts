import { useEffect } from "react";

/**
 * "No-inspect" friction layer for the dashboard.
 *
 * <p><b>Honest scope</b>: this CANNOT prevent a determined user from
 * inspecting the site. The HTML/JS has to reach the browser to render,
 * and any of these defeat every block below in seconds:
 *
 * <ul>
 *   <li>Disable JavaScript in browser settings → every handler here is dead.</li>
 *   <li>Browser menu → Inspect (the menu item, not a keyboard shortcut).</li>
 *   <li>External proxies: mitmproxy, Burp, Charles, Fiddler.</li>
 *   <li>{@code curl}/SDK calls directly against the API.</li>
 *   <li>Save Page As before this hook even mounts.</li>
 * </ul>
 *
 * <p>The blocks below stop the casual user who'd accidentally hit F12 or
 * right-click → Inspect. Active in BOTH dev and prod by default — set
 * {@code VITE_NO_INSPECT_OFF=1} in {@code .env.local} to disable while
 * working on the dashboard locally.
 *
 * <p>The REAL line of defence is server-side: auth on every endpoint,
 * ownership checks before any sk_ key is touched, never trust the client.
 * That's already in place — this is sugar on top.
 */
export function useNoInspect(
    enabled: boolean = import.meta.env.VITE_NO_INSPECT_OFF !== "1",
) {
  useEffect(() => {
    if (!enabled) return;

    // Flip a body-level data attribute the CSS keys off of so the
    // user-select / drag blocks only apply in production.
    document.body.setAttribute("data-no-inspect", "true");

    const onKey = (e: KeyboardEvent) => {
      // F12
      if (e.key === "F12") {
        e.preventDefault();
        return;
      }
      // Ctrl/Cmd + Shift + I  (DevTools)
      // Ctrl/Cmd + Shift + J  (Console)
      // Ctrl/Cmd + Shift + C  (Inspect element picker)
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.shiftKey && ["I", "J", "C", "i", "j", "c"].includes(e.key)) {
        e.preventDefault();
        return;
      }
      // Ctrl/Cmd + U  (View source)
      // Ctrl/Cmd + S  (Save page)
      if (mod && (e.key === "u" || e.key === "U" || e.key === "s" || e.key === "S")) {
        e.preventDefault();
        return;
      }
    };

    const onContextMenu = (e: MouseEvent) => {
      // Block right-click → "Inspect element" entry from the context menu.
      e.preventDefault();
    };

    // Drag-and-drop of images / links sometimes leaks files; cheap to block.
    const onDragStart = (e: DragEvent) => {
      const t = e.target as HTMLElement | null;
      if (!t) return;
      if (t.tagName === "IMG" || t.tagName === "A") {
        e.preventDefault();
      }
    };

    document.addEventListener("keydown", onKey, true);
    document.addEventListener("contextmenu", onContextMenu, true);
    document.addEventListener("dragstart", onDragStart, true);

    return () => {
      document.body.removeAttribute("data-no-inspect");
      document.removeEventListener("keydown", onKey, true);
      document.removeEventListener("contextmenu", onContextMenu, true);
      document.removeEventListener("dragstart", onDragStart, true);
    };
  }, [enabled]);
}
