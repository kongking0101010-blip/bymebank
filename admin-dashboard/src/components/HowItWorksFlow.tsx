import { useEffect, useRef, useState } from "react";
import { motion, useInView } from "framer-motion";
import {
  MousePointerClick, ScanQrCode, KeyRound,
  type LucideIcon,
} from "lucide-react";

/**
 * "Live in three steps" — restrained, professional flow.
 *
 * Design notes
 * ────────────
 * • One accent color (brand). No rainbow gradients per step.
 * • Monochrome glyph icons in soft squares — no glossy 3D discs.
 * • A single thin connector that draws once, then a subtle dot
 *   slides between steps.
 * • Numbers are hairline strokes, not loud watermark fills.
 * • Hover any card to dwell on it. No "auto-playing" badge.
 */

type Step = {
  n: number;
  icon: LucideIcon;
  title: string;
  body: string;
};

const STEPS: Step[] = [
  {
    n: 1,
    icon: MousePointerClick,
    title: "Sign in",
    body: "Google one-tap or Gmail one-time code. Stays signed in for a year.",
  },
  {
    n: 2,
    icon: ScanQrCode,
    title: "Link your bank",
    body: "Paste a KHQR string or drop the image. We decode the merchant info for you.",
  },
  {
    n: 3,
    icon: KeyRound,
    title: "Mint sk_ key",
    body: "Your API key is shown once — copy it, store it, start charging.",
  },
];

const STEP_DURATION_MS = 3_200;

export function HowItWorksFlow() {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const inView = useInView(containerRef, { once: true, margin: "-20%" });
  const [active, setActive] = useState(0);
  const [paused, setPaused] = useState(false);

  // Auto-advance the active step. Pauses on hover.
  useEffect(() => {
    if (!inView || paused) return;
    const id = window.setInterval(
      () => setActive((i) => (i + 1) % STEPS.length),
      STEP_DURATION_MS,
    );
    return () => window.clearInterval(id);
  }, [inView, paused]);

  return (
    <div ref={containerRef} className="relative">
      {/* ── DESKTOP ─────────────────────────────────────────────────── */}
      <div className="hidden md:block">
        <div className="relative">
          {/* Hairline connector — runs through the icon row of the cards.
              Static dashed track + a moving dot that hops between steps. */}
          <div
            aria-hidden
            className="absolute left-[8.333%] right-[8.333%] top-[68px] h-px"
            style={{
              backgroundImage:
                "linear-gradient(90deg, rgba(15,23,42,.18) 50%, transparent 0%)",
              backgroundSize: "10px 1px",
              backgroundRepeat: "repeat-x",
            }}
          />
          {inView && (
            <motion.span
              aria-hidden
              className="absolute top-[64px] h-[9px] w-[9px] rounded-full bg-ink-900 ring-4 ring-white"
              style={{ left: "8.333%" }}
              animate={{
                left: ["8.333%", "50%", "91.666%", "50%", "8.333%"],
              }}
              transition={{
                duration: (STEP_DURATION_MS * STEPS.length) / 1000,
                ease: "easeInOut",
                repeat: Infinity,
              }}
            />
          )}

          {/* Step cards */}
          <ol className="relative grid grid-cols-3 gap-8">
            {STEPS.map((s, i) => (
              <FlowCard
                key={s.n}
                step={s}
                index={i}
                active={i === active}
                done={i < active}
                inView={inView}
                onEnter={() => { setPaused(true); setActive(i); }}
                onLeave={() => setPaused(false)}
              />
            ))}
          </ol>
        </div>
      </div>

      {/* ── MOBILE: vertical list ───────────────────────────────────── */}
      <div className="md:hidden">
        <ol className="relative space-y-5 pl-12">
          <span
            aria-hidden
            className="absolute left-5 top-3 bottom-3 w-px bg-ink-200"
          />
          {STEPS.map((s, i) => (
            <li key={s.n} className="relative">
              <span
                className="absolute -left-12 top-3 h-9 w-9 rounded-xl grid place-items-center text-ink-700 ring-1 ring-ink-200 bg-white"
              >
                <s.icon size={16} strokeWidth={1.75}/>
              </span>
              <div className="rounded-xl bg-white ring-1 ring-ink-100 p-5">
                <div className="text-[10px] uppercase tracking-[0.18em] font-semibold text-ink-400">
                  Step {s.n}
                </div>
                <div className="mt-1 text-base font-semibold text-ink-900">
                  {s.title}
                </div>
                <p className="mt-1 text-sm text-ink-500 leading-snug">{s.body}</p>
              </div>
              {void i}
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

function FlowCard({
  step, index, active, inView, onEnter, onLeave,
}: {
  step: Step;
  index: number;
  active: boolean;
  done: boolean;
  inView: boolean;
  onEnter: () => void;
  onLeave: () => void;
}) {
  return (
    <motion.li
      initial={{ opacity: 0, y: 12 }}
      animate={inView ? { opacity: 1, y: 0 } : {}}
      transition={{ duration: 0.5, delay: 0.1 + index * 0.08, ease: [0.21, 0.61, 0.35, 1] }}
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      className="relative"
    >
      <div
        className={`relative rounded-2xl bg-white px-7 py-7 ring-1 transition-all duration-300 ease-out ${
          active
            ? "ring-ink-900/15 shadow-[0_18px_50px_-24px_rgba(15,23,42,0.30)]"
            : "ring-ink-100 shadow-[0_1px_2px_rgba(15,23,42,0.04)]"
        }`}
      >
        {/* Step number — hairline, restrained, top-right */}
        <span className="absolute top-5 right-6 text-[11px] font-mono tabular-nums tracking-tight text-ink-400">
          0{step.n}
        </span>

        {/* Icon — neutral square, ink-on-white. Active state inverts. */}
        <motion.div
          animate={{
            backgroundColor: active ? "#0f172a" : "#ffffff",
            color:           active ? "#ffffff" : "#1f2937",
            borderColor:     active ? "#0f172a" : "#e5e7eb",
          }}
          transition={{ duration: 0.35, ease: "easeOut" }}
          className="h-11 w-11 rounded-xl border grid place-items-center"
          style={{ borderWidth: 1 }}
        >
          <step.icon size={18} strokeWidth={1.75}/>
        </motion.div>

        {/* Title + body */}
        <div className="mt-5">
          <h3 className="text-[17px] font-semibold tracking-tight text-ink-900">
            {step.title}
          </h3>
          <p className="mt-1.5 text-[14px] leading-[1.55] text-ink-500">
            {step.body}
          </p>
        </div>

        {/* Hairline progress at the bottom — subtle, not a bar */}
        <div className="mt-7 relative h-px bg-ink-100 overflow-hidden">
          <motion.span
            aria-hidden
            className="absolute inset-y-0 left-0 bg-ink-900"
            animate={{ width: active ? "100%" : "0%" }}
            transition={{
              duration: active ? STEP_DURATION_MS / 1000 : 0.4,
              ease: active ? "linear" : "easeOut",
            }}
          />
        </div>
      </div>
    </motion.li>
  );
}
