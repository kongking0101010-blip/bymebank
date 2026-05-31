import { useEffect, useMemo, useRef, useState } from "react";
import { motion, useInView } from "framer-motion";
import { Link } from "react-router-dom";
import { ArrowRight } from "lucide-react";

/**
 * Final CTA — "Ship your first KHQR payment today" + a code panel that
 * types itself out when the section enters view.
 *
 * Animation contract
 * ──────────────────
 *  • Eyebrow + headline + body fade up sequentially.
 *  • Code editor gets a "compiling…" status, then the snippet types itself
 *    out at ~30ms/char with a blinking caret.
 *  • When the type-out finishes, status flips to "200 OK · 184 ms".
 *  • A faint blue light glides across the editor like a scanline, once.
 *
 * Restrained — no rainbow gradients, no sparkles, dark-mode product feel.
 */

interface FinalCTAProps {
  signedIn: boolean;
  firstName?: string | null;
}

type Tok = { c: TokColor; t: string };
type TokColor =
  | "comment" | "white" | "key" | "string" | "number" | "fn" | "punct";

const COLOR: Record<TokColor, string> = {
  comment: "#64748b",
  white:   "#e5e7eb",
  key:     "#7dd3fc",
  string:  "#fcd34d",
  number:  "#f0abfc",
  fn:      "#0ecf81",
  punct:   "#94a3b8",
};

// Each entry is one "row" of tokens. The newline at the end of the row
// is implicit so we get nice line-by-line typing.
const SNIPPET: Tok[][] = [
  [{ c: "key", t: "import " }, { c: "punct", t: "{ " }, { c: "white", t: "BymeBank" }, { c: "punct", t: " } " }, { c: "key", t: "from " }, { c: "string", t: "\"bymebank\"" }, { c: "punct", t: ";" }],
  [],
  [{ c: "comment", t: "// one client, every Cambodian bank" }],
  [{ c: "key", t: "const " }, { c: "white", t: "kb " }, { c: "punct", t: "= " }, { c: "key", t: "new " }, { c: "fn", t: "BymeBank" }, { c: "punct", t: "(" }, { c: "string", t: "\"sk_•••••\"" }, { c: "punct", t: ");" }],
  [],
  [{ c: "comment", t: "// charge $12.50 via Bakong" }],
  [{ c: "key", t: "const " }, { c: "white", t: "charge " }, { c: "punct", t: "= " }, { c: "key", t: "await " }, { c: "white", t: "kb.payments." }, { c: "fn", t: "qr" }, { c: "punct", t: "({" }],
  [{ c: "white", t: "  bank:   " }, { c: "string", t: "\"BAKONG\"" }, { c: "punct", t: "," }],
  [{ c: "white", t: "  amount: " }, { c: "number", t: "12.50" }, { c: "punct", t: "," }],
  [{ c: "punct", t: "});" }],
  [],
  [{ c: "fn", t: "console" }, { c: "punct", t: "." }, { c: "fn", t: "log" }, { c: "punct", t: "(" }, { c: "white", t: "charge.qrImage" }, { c: "punct", t: ");" }],
];

const TYPE_SPEED_MS = 18;   // ms per character
const POST_DELAY_MS = 600;  // pause after typing finishes before flipping status

export function FinalCTA({ signedIn, firstName }: FinalCTAProps) {
  const sectionRef = useRef<HTMLDivElement | null>(null);
  const inView = useInView(sectionRef, { once: true, margin: "-15%" });

  // ── precompute every line as a flat list of {color, char} pairs so we
  //    can reveal them character by character.
  const flat = useMemo(() => {
    const out: { line: number; color: TokColor; ch: string }[] = [];
    SNIPPET.forEach((line, i) => {
      line.forEach((tok) => {
        for (const ch of tok.t) out.push({ line: i, color: tok.c, ch });
      });
    });
    return out;
  }, []);
  const totalChars = flat.length;

  const [typed, setTyped] = useState(0);
  const [status, setStatus] = useState<"idle" | "compiling" | "ok">("idle");

  // Drive the typing animation off rAF so it stays smooth even when the
  // tab is busy doing layout.
  useEffect(() => {
    if (!inView) return;
    setStatus("compiling");
    setTyped(0);
    const started = performance.now();
    let raf = 0;
    const tick = () => {
      const elapsed = performance.now() - started;
      const next = Math.min(totalChars, Math.floor(elapsed / TYPE_SPEED_MS));
      setTyped(next);
      if (next < totalChars) {
        raf = requestAnimationFrame(tick);
      } else {
        // Brief pause, then mark request "200 OK" — feels like the snippet
        // just executed.
        setTimeout(() => setStatus("ok"), POST_DELAY_MS);
      }
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [inView, totalChars]);

  // Group "typed" chars back into JSX lines.
  const lines = useMemo(() => {
    const visible = flat.slice(0, typed);
    const grouped: { line: number; color: TokColor; text: string }[][] = [];
    let curLine = -1;
    let curColor: TokColor | null = null;
    let buf = "";
    let row: { line: number; color: TokColor; text: string }[] = [];

    const flush = () => {
      if (buf.length === 0 || curColor === null) return;
      row.push({ line: curLine, color: curColor, text: buf });
      buf = "";
    };

    for (const item of visible) {
      if (item.line !== curLine) {
        flush();
        if (curLine !== -1) grouped[curLine] = row;
        curLine = item.line;
        row = grouped[curLine] ?? [];
        curColor = null;
      }
      if (item.color !== curColor) {
        flush();
        curColor = item.color;
      }
      buf += item.ch;
    }
    flush();
    if (curLine !== -1) grouped[curLine] = row;

    // Pad missing lines with empty arrays so blank lines render with their
    // height intact.
    for (let i = 0; i <= (curLine === -1 ? -1 : curLine); i++) {
      if (!grouped[i]) grouped[i] = [];
    }
    return grouped;
  }, [flat, typed]);

  return (
    <section ref={sectionRef} className="py-28">
      <div className="max-w-6xl mx-auto px-6">
        <div className="relative overflow-hidden rounded-[28px] bg-[#0b0e1a] ring-1 ring-white/[.06]">
          {/* Top hairline */}
          <span className="pointer-events-none absolute inset-x-0 top-0 h-px bg-white/10"/>
          {/* Soft single-source brand light from upper-left */}
          <div
            aria-hidden
            className="pointer-events-none absolute -top-32 -left-32 h-[420px] w-[420px] rounded-full opacity-60 blur-3xl"
            style={{ background: "radial-gradient(circle, #2535f5 0%, transparent 60%)" }}
          />
          {/* Subtle grid */}
          <div
            aria-hidden
            className="pointer-events-none absolute inset-0 opacity-[.05]"
            style={{
              backgroundImage:
                "linear-gradient(to right, #ffffff 1px, transparent 1px)," +
                "linear-gradient(to bottom, #ffffff 1px, transparent 1px)",
              backgroundSize: "48px 48px",
              maskImage: "radial-gradient(ellipse at 0% 0%, black 30%, transparent 70%)",
            }}
          />

          <div className="relative grid lg:grid-cols-[1.1fr_1fr] gap-10 lg:gap-14 px-8 sm:px-12 py-14 lg:py-16 text-white">
            {/* Left: copy with sequential reveal */}
            <div className="flex flex-col">
              <motion.span
                initial={{ opacity: 0, y: 6 }}
                animate={inView ? { opacity: 1, y: 0 } : {}}
                transition={{ duration: 0.5, ease: "easeOut" }}
                className="inline-flex items-center gap-2 self-start text-[11px] font-mono tracking-tight text-white/60"
              >
                <span className="relative flex h-1.5 w-1.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400/70"/>
                  <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-emerald-400"/>
                </span>
                ready when you are
              </motion.span>

              {signedIn ? (
                <RevealHeading inView={inView} delay={0.08}>
                  Welcome back,
                  <br/>
                  <span className="text-white/60">{firstName ?? "there"}.</span>
                </RevealHeading>
              ) : (
                <RevealHeading inView={inView} delay={0.08}>
                  Ship your first
                  <br/>
                  <span className="text-white/60">KHQR payment today.</span>
                </RevealHeading>
              )}

              <motion.p
                initial={{ opacity: 0, y: 8 }}
                animate={inView ? { opacity: 1, y: 0 } : {}}
                transition={{ duration: 0.55, delay: 0.18, ease: "easeOut" }}
                className="mt-5 text-[15px] text-white/65 max-w-md leading-relaxed"
              >
                Sign in with Gmail. Paste your bank QR. Copy your{" "}
                <code className="font-mono text-white/85">sk_</code> key. That's the whole flow.
              </motion.p>

              <motion.div
                initial={{ opacity: 0, y: 8 }}
                animate={inView ? { opacity: 1, y: 0 } : {}}
                transition={{ duration: 0.55, delay: 0.26, ease: "easeOut" }}
                className="mt-8 flex flex-wrap items-center gap-3"
              >
                {signedIn ? (
                  <Link
                    to="/app"
                    className="group inline-flex items-center gap-2 rounded-full bg-white text-ink-900 px-5 py-2.5 text-sm font-semibold hover:bg-white/90 transition"
                  >
                    Open dashboard
                    <ArrowRight size={15} className="transition-transform group-hover:translate-x-0.5"/>
                  </Link>
                ) : (
                  <Link
                    to="/login"
                    className="group inline-flex items-center gap-2 rounded-full bg-white text-ink-900 px-5 py-2.5 text-sm font-semibold hover:bg-white/90 transition"
                  >
                    Get your API key
                    <ArrowRight size={15} className="transition-transform group-hover:translate-x-0.5"/>
                  </Link>
                )}
                <a
                  href="#how-it-works"
                  className="group inline-flex items-center gap-1.5 rounded-full text-sm font-medium text-white/70 hover:text-white px-2.5 py-2.5 transition"
                >
                  See how it works
                  <span aria-hidden className="transition-transform group-hover:translate-x-0.5">→</span>
                </a>
              </motion.div>

              <motion.div
                initial={{ opacity: 0 }}
                animate={inView ? { opacity: 1 } : {}}
                transition={{ duration: 0.55, delay: 0.34 }}
                className="mt-10 flex items-center gap-6 text-[11px] text-white/40 font-mono"
              >
                <span>FREE during launch</span>
                <span className="h-3 w-px bg-white/10"/>
                <span>NO CREDIT CARD</span>
                <span className="h-3 w-px bg-white/10"/>
                <span>~60s setup</span>
              </motion.div>
            </div>

            {/* Right: typing code editor */}
            <motion.div
              initial={{ opacity: 0, y: 14 }}
              animate={inView ? { opacity: 1, y: 0 } : {}}
              transition={{ duration: 0.55, delay: 0.12, ease: "easeOut" }}
              className="relative flex items-center"
            >
              <div className="relative w-full rounded-2xl bg-[#06091a] ring-1 ring-white/[.06] overflow-hidden shadow-[0_20px_60px_-20px_rgba(0,0,0,.8)]">
                {/* Scanline that sweeps across once when in view */}
                {inView && (
                  <motion.span
                    aria-hidden
                    className="pointer-events-none absolute inset-y-0 w-24 -skew-x-12"
                    style={{
                      background:
                        "linear-gradient(90deg, transparent 0%, rgba(58,85,255,0.18) 50%, transparent 100%)",
                    }}
                    initial={{ left: "-15%" }}
                    animate={{ left: "115%" }}
                    transition={{ duration: 2.2, ease: "easeOut", delay: 0.3 }}
                  />
                )}

                {/* Title bar */}
                <div className="flex items-center gap-1.5 px-4 py-2.5 border-b border-white/[.05] bg-white/[.02]">
                  <span className="h-2.5 w-2.5 rounded-full bg-rose-500/70"/>
                  <span className="h-2.5 w-2.5 rounded-full bg-amber-500/70"/>
                  <span className="h-2.5 w-2.5 rounded-full bg-emerald-500/70"/>
                  <span className="ml-3 text-[11px] font-mono text-white/40">charge.ts</span>
                  <span className="ml-auto text-[10px] font-mono text-white/30">TypeScript</span>
                </div>

                {/* Code body */}
                <pre className="m-0 px-5 py-5 text-[12.5px] leading-[1.7] font-mono text-white/85 min-h-[260px] grid grid-cols-[28px_1fr] gap-x-3">
                  <span aria-hidden className="text-right text-white/20 select-none">
                    {SNIPPET.map((_, i) => (
                      <div key={i}>{String(i + 1).padStart(2, "0")}</div>
                    ))}
                  </span>
                  <span>
                    {SNIPPET.map((_, i) => {
                      const row = lines[i] ?? [];
                      const isLastRowTyping = i === (typed === 0 ? 0 : findLineForIndex(flat, typed - 1));
                      const stillTyping = typed < totalChars;
                      return (
                        <div key={i} className="whitespace-pre">
                          {row.length === 0 ? "\u00A0" : row.map((seg, k) => (
                            <span key={k} style={{ color: COLOR[seg.color] }}>
                              {seg.text}
                            </span>
                          ))}
                          {stillTyping && isLastRowTyping && <Caret/>}
                        </div>
                      );
                    })}
                  </span>
                </pre>

                {/* Status footer */}
                <div className="flex items-center justify-between px-5 py-2.5 border-t border-white/[.05] bg-white/[.02] text-[10px] font-mono">
                  <StatusIndicator state={status}/>
                  <span className="text-white/40 tabular-nums">
                    {status === "ok" ? "184 ms" : "—"}
                  </span>
                </div>
              </div>
            </motion.div>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ─────────────────── helpers ─────────────────── */

function findLineForIndex(
  flat: { line: number; color: TokColor; ch: string }[],
  idx: number,
) {
  return flat[Math.max(0, Math.min(flat.length - 1, idx))]?.line ?? 0;
}

function Caret() {
  return (
    <span
      aria-hidden
      className="ml-px inline-block h-[13px] w-[7px] -mb-0.5 align-baseline animate-[blink_1s_steps(1)_infinite]"
      style={{ background: "#0ecf81" }}
    />
  );
}

function StatusIndicator({ state }: { state: "idle" | "compiling" | "ok" }) {
  if (state === "ok") {
    return (
      <span className="text-white/55 inline-flex items-center gap-1.5">
        <span className="text-emerald-400">●</span> 200 OK
      </span>
    );
  }
  if (state === "compiling") {
    return (
      <span className="text-white/55 inline-flex items-center gap-1.5">
        <span className="text-amber-400 animate-pulse">●</span> compiling…
      </span>
    );
  }
  return <span className="text-white/30">—</span>;
}

function RevealHeading({
  children, inView, delay = 0,
}: {
  children: React.ReactNode;
  inView: boolean;
  delay?: number;
}) {
  return (
    <motion.h3
      initial={{ opacity: 0, y: 14 }}
      animate={inView ? { opacity: 1, y: 0 } : {}}
      transition={{ duration: 0.6, delay, ease: [0.21, 0.61, 0.35, 1] }}
      className="mt-5 text-4xl sm:text-5xl font-bold tracking-tight leading-[1.05]"
    >
      {children}
    </motion.h3>
  );
}
