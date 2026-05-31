import { animate, useInView, useMotionValue, useTransform } from "framer-motion";
import { useEffect, useRef } from "react";

interface CounterProps {
  to: number;
  /** Number of decimal places to show (e.g. 2 for "12.50"). */
  decimals?: number;
  /** Prepend (e.g. "$") or append (e.g. "ms") units. */
  prefix?: string;
  suffix?: string;
  /** Animation duration in seconds. */
  duration?: number;
  className?: string;
}

/** A number that smoothly counts up the first time it scrolls into view. */
export function Counter({
  to, decimals = 0, prefix = "", suffix = "", duration = 1.2, className,
}: CounterProps) {
  const ref = useRef<HTMLSpanElement | null>(null);
  const inView = useInView(ref, { once: true, margin: "-30px" });
  const mv = useMotionValue(0);
  const text = useTransform(mv, (v) =>
      `${prefix}${v.toFixed(decimals)}${suffix}`);

  useEffect(() => {
    if (!inView) return;
    const controls = animate(mv, to, {
      duration, ease: "easeOut",
      onUpdate: (v) => { if (ref.current) ref.current.textContent =
          `${prefix}${v.toFixed(decimals)}${suffix}`; },
    });
    return controls.stop;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inView, to]);

  // initial render — show 0 so SSR doesn't flash the final value
  void text;
  return <span ref={ref} className={className}>{`${prefix}${(0).toFixed(decimals)}${suffix}`}</span>;
}
