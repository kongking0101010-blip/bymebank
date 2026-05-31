import { useRef } from "react";
import { motion, useMotionValue, useSpring, useTransform } from "framer-motion";
import { ArrowUpRight, type LucideIcon } from "lucide-react";

/**
 * A single feature tile in the bento grid.
 *
 * Design notes
 * ────────────
 * • One restrained palette — white card on white, hairline ring.
 * • Icon disc is monochrome (ink-on-white) by default and inverts (white
 *   glyph on ink-900) on hover. No rainbow per-card gradients.
 * • Hover triggers a faint magnetic tilt (-3° → 3°) on the icon based on
 *   cursor position, plus a thin top accent that wipes in from left.
 * • A small uppercase metric and a "Learn more →" link reveal on hover —
 *   gives every card the density of a real product feature page.
 * • Active card border darkens; the rest stay subtle. No glow halos.
 */
interface FeatureCardProps {
  icon: LucideIcon;
  title: string;
  text: string;
  metric?: string;
  span?: string;
  index: number;
}

export function FeatureCard({
  icon: Icon, title, text, metric, span = "", index,
}: FeatureCardProps) {
  const ref = useRef<HTMLDivElement | null>(null);

  // Magnetic icon: cursor coordinates feed into a spring → tilt + shift.
  const px = useMotionValue(0.5);   // 0..1
  const py = useMotionValue(0.5);
  const sx = useSpring(px, { stiffness: 220, damping: 22 });
  const sy = useSpring(py, { stiffness: 220, damping: 22 });
  const rotateY = useTransform(sx, [0, 1], [-6, 6]);
  const rotateX = useTransform(sy, [0, 1], [6, -6]);
  const transX  = useTransform(sx, [0, 1], [-2, 2]);
  const transY  = useTransform(sy, [0, 1], [-2, 2]);

  function onMove(e: React.MouseEvent<HTMLDivElement>) {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    px.set((e.clientX - r.left) / r.width);
    py.set((e.clientY - r.top)  / r.height);
    // Spotlight position used by the CSS radial gradient
    el.style.setProperty("--mx", `${e.clientX - r.left}px`);
    el.style.setProperty("--my", `${e.clientY - r.top}px`);
  }

  function onLeave() {
    px.set(0.5);
    py.set(0.5);
  }

  return (
    <motion.div
      ref={ref}
      initial={{ opacity: 0, y: 20 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: "-50px" }}
      transition={{ duration: 0.4, delay: index * 0.05, ease: [0.21, 0.61, 0.35, 1] }}
      onMouseMove={onMove}
      onMouseLeave={onLeave}
      className={`group relative overflow-hidden rounded-2xl bg-white ring-1 ring-ink-100
                  hover:ring-ink-900/20 transition-shadow duration-300
                  hover:shadow-[0_24px_60px_-30px_rgba(15,23,42,0.30)] ${span}`}
      style={{ perspective: 1000 }}
    >
      {/* Spotlight that follows the cursor */}
      <span
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-300"
        style={{
          background:
            "radial-gradient(420px circle at var(--mx,50%) var(--my,50%), rgba(15,23,42,0.05), transparent 45%)",
        }}
      />

      {/* Top hairline accent that wipes in from the left on hover */}
      <span
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 h-px origin-left scale-x-0 group-hover:scale-x-100 bg-ink-900 transition-transform duration-500 ease-out"
      />

      <div className="relative p-7 sm:p-8">
        {/* Icon disc — magnetic tilt + invert-on-hover */}
        <motion.div
          style={{
            rotateX,
            rotateY,
            x: transX,
            y: transY,
            transformStyle: "preserve-3d",
          }}
          className="h-12 w-12 rounded-xl border border-ink-200 bg-white text-ink-700
                     grid place-items-center transition-colors duration-300
                     group-hover:bg-ink-900 group-hover:text-white group-hover:border-ink-900"
        >
          <Icon size={20} strokeWidth={1.75} className="transition-transform duration-300 group-hover:-rotate-3"/>
        </motion.div>

        {/* Optional metric eyebrow */}
        {metric && (
          <div className="mt-5 text-[11px] uppercase tracking-[0.18em] font-semibold text-ink-500">
            {metric}
          </div>
        )}

        {/* Title + body */}
        <h3 className="mt-3 text-[18px] font-semibold tracking-tight text-ink-900">
          {title}
        </h3>
        <p className="mt-1.5 text-[14.5px] leading-[1.55] text-ink-500">
          {text}
        </p>

        {/* Learn more link reveal */}
        <div className="mt-5 inline-flex items-center gap-1 text-[13px] font-medium text-ink-900 opacity-0 -translate-y-1 group-hover:opacity-100 group-hover:translate-y-0 transition-all duration-300">
          <span className="relative">
            Learn more
            <span className="absolute -bottom-0.5 left-0 right-0 h-px bg-ink-900 origin-left scale-x-0 group-hover:scale-x-100 transition-transform duration-300"/>
          </span>
          <ArrowUpRight size={14} strokeWidth={2}/>
        </div>
      </div>
    </motion.div>
  );
}
