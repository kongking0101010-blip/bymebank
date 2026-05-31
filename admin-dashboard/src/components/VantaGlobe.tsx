import { useEffect, useRef } from "react";
import * as THREE from "three";
// vanta has no TypeScript types, so we cast.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore
import GLOBE from "vanta/dist/vanta.globe.min";

interface VantaGlobeProps {
  /** Optional Tailwind classes for the wrapping div. */
  className?: string;
  /** Hex (number) for the background color. */
  backgroundColor?: number;
  /** Hex (number) for the line color. */
  color?: number;
  /** Hex (number) for the second / dot color. */
  color2?: number;
  /** Visual scale (1.0 default). */
  scale?: number;
  /** Mobile scale (1.0 default). */
  scaleMobile?: number;
}

/**
 * React wrapper around Vanta's GLOBE animated background.
 *
 * Renders a full-bleed canvas inside the wrapping div. Pin the wrapper
 * absolutely on top of (or as) the parent's background.
 *
 * Respects {@code prefers-reduced-motion} — falls back to a static dark
 * background so users with motion sensitivity don't get a spinning globe.
 */
export function VantaGlobe({
  className = "",
  backgroundColor = 0x11154d,   // brand-950
  color           = 0x3a55ff,   // brand-500
  color2          = 0x0ecf81,   // accent-500
  scale           = 1,
  scaleMobile     = 1,
}: VantaGlobeProps) {
  const ref = useRef<HTMLDivElement | null>(null);
  const effect = useRef<{ destroy?: () => void } | null>(null);

  useEffect(() => {
    if (!ref.current) return;
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    if (reduce) return; // skip animation entirely

    effect.current = GLOBE({
      el: ref.current,
      THREE,
      mouseControls: true,
      touchControls: true,
      gyroControls: false,
      minHeight: 200,
      minWidth: 200,
      scale,
      scaleMobile,
      backgroundColor,
      color,
      color2,
      // Slightly slower than default so it feels classy, not busy.
      size: 1,
    });

    return () => {
      try { effect.current?.destroy?.(); }
      catch { /* ignore double-destroy */ }
      effect.current = null;
    };
  }, [backgroundColor, color, color2, scale, scaleMobile]);

  return <div ref={ref} className={className}/>;
}
