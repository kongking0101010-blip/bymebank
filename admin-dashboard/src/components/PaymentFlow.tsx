import { motion, AnimatePresence } from "framer-motion";
import { useEffect, useMemo, useState } from "react";
import {
  User as UserIcon, KeyRound, ScanQrCode, Server, Building2, Webhook,
  Coins, Mail, ArrowRight, CircleCheckBig, Activity, Lock,
  type LucideIcon,
} from "lucide-react";

/**
 * Hero "How it works" — looping cinematic split into two acts.
 *
 *   Act 1 — Onboarding   (get the API key)
 *      You ── verify email ── link bank QR ── mint sk_
 *   Act 2 — Live charge  (use the key)
 *      Customer ─► API ─► Bakong ─► Webhook ─► Paid
 *
 * Designed to feel like a real Stripe / Linear / Vercel product video.
 *
 * Interactions
 * ────────────
 *  - Hover the card → the auto-loop pauses so you can read.
 *  - Click an Act tab → animated pill jumps to the first step of that act.
 *  - Click any scrubber bar → jump straight to that step.
 */

const STEP_MS = 2_400;

type Act = 0 | 1;
type NodeId =
  | "you" | "signin" | "link" | "key"
  | "customer" | "api" | "bank" | "webhook" | "paid";

type Color  =
  | "pink" | "ink-dim" | "emerald" | "sky" | "amber"
  | "fuchsia" | "violet" | "white";
type ColoredLine = { c: Color; t: string };
const c = (col: Color, t: string): ColoredLine => ({ c: col, t });

interface Step {
  id: number;
  act: Act;
  who: NodeId;
  title: string;
  detail: string;
  badge: string;
  elapsed: string;
  /** "REQ" | "RES" | "EVT" — what's being shown in the code panel */
  kind: "REQ" | "RES" | "EVT";
  /** Status pill on the right of the code-panel title bar */
  status: { code: string; tone: "ok" | "warn" | "info" };
  code: ColoredLine[][];
}

/* ─────────────────── the storyboard ─────────────────── */

const STEPS: Step[] = [
  /* ─── ACT 1 — Onboarding (get the API key) ─── */
  { id: 0, act: 0, who: "you",
    title: "Sign in to Byme Bank",
    detail: "Use your Gmail with a one-time code, or Google one-tap. No password needed at first.",
    badge: "ONBOARDING", elapsed: "00:00", kind: "REQ",
    status: { code: "200 OK", tone: "ok" },
    code: [
      [c("pink","POST"), c("white"," /auth/email/request")],
      [c("ink-dim","{ "), c("sky","\"email\""), c("ink-dim",": "),
       c("amber","\"you@gmail.com\""), c("ink-dim"," }")],
      [c("ink-dim","→ "), c("emerald","code sent")],
    ],
  },
  { id: 1, act: 0, who: "signin",
    title: "Verify with 6-digit code",
    detail: "We email a code that lasts 10 minutes. Type it in and stay signed in for a full year.",
    badge: "ONBOARDING", elapsed: "00:08", kind: "REQ",
    status: { code: "200 OK", tone: "ok" },
    code: [
      [c("pink","POST"), c("white"," /auth/email/verify")],
      [c("ink-dim","{ "), c("sky","\"code\""), c("ink-dim",": "),
       c("emerald","\"382017\""), c("ink-dim"," }")],
      [c("ink-dim","→ "), c("emerald","JWT · valid 365d")],
    ],
  },
  { id: 2, act: 0, who: "link",
    title: "Link your bank QR",
    detail: "Paste the KHQR string from ABA, ACLEDA, Wing or Bakong — we decode it for you.",
    badge: "ONBOARDING", elapsed: "00:24", kind: "REQ",
    status: { code: "201 CREATED", tone: "ok" },
    code: [
      [c("pink","POST"), c("white"," /api/v1/merchants/upload")],
      [c("ink-dim","qrString: "), c("emerald","\"00020101…\"")],
      [c("ink-dim","bank:     "), c("amber","\"BAKONG\"")],
      [c("ink-dim","→ "), c("emerald","merchant verified")],
    ],
  },
  { id: 3, act: 0, who: "key",
    title: "Mint your sk_ key",
    detail: "We issue a real merchant key from the live KHQR network. Yours forever.",
    badge: "ONBOARDING", elapsed: "00:42", kind: "RES",
    status: { code: "200 OK", tone: "ok" },
    code: [
      [c("pink","POST"), c("white"," /api/v1/me/upstream-key/refresh")],
      [c("ink-dim","→ "), c("emerald","sk_4e7822854c81c61e56e6f830f5046020")],
      [c("ink-dim","expires_in_days: "), c("fuchsia","30")],
    ],
  },

  /* ─── ACT 2 — Live charge (use the key) ─── */
  { id: 4, act: 1, who: "customer",
    title: "Customer scans your QR",
    detail: "User opens any KHQR-enabled wallet and scans your merchant code.",
    badge: "LIVE CHARGE", elapsed: "0 ms", kind: "EVT",
    status: { code: "scan", tone: "info" },
    code: [
      [c("ink-dim","scan ▸ "), c("emerald","KHQR")],
      [c("ink-dim","amount:  "), c("fuchsia","12.50"), c("ink-dim"," USD")],
    ],
  },
  { id: 5, act: 1, who: "api",
    title: "POST /payments/qr",
    detail: "Your server hits our API. We sign the request and dispatch to the right bank.",
    badge: "LIVE CHARGE", elapsed: "184 ms", kind: "REQ",
    status: { code: "200 OK", tone: "ok" },
    code: [
      [c("pink","POST"), c("white"," /api/v1/payments/qr")],
      [c("ink-dim","X-API-Key: "), c("emerald","sk_•••••")],
      [c("ink-dim","{ "), c("sky","\"bank\""), c("ink-dim",": "), c("amber","\"BAKONG\""), c("ink-dim",", "),
       c("sky","\"amount\""), c("ink-dim",": "), c("fuchsia","12.50"), c("ink-dim"," }")],
    ],
  },
  { id: 6, act: 1, who: "bank",
    title: "Bakong settlement",
    detail: "Cambodia's national rails index the MD5 hash and route the funds in real time.",
    badge: "LIVE CHARGE", elapsed: "320 ms", kind: "EVT",
    status: { code: "SETTLED", tone: "warn" },
    code: [
      [c("violet","→ bakong.nbc")],
      [c("ink-dim","md5:    "), c("white","9c4a6d8d873d…")],
      [c("ink-dim","status: "), c("amber","SETTLED")],
    ],
  },
  { id: 7, act: 1, who: "webhook",
    title: "Signed webhook fires",
    detail: "We push an HMAC-signed POST to your callback so you can fulfill the order instantly.",
    badge: "LIVE CHARGE", elapsed: "1.6 s", kind: "REQ",
    status: { code: "hmac256", tone: "info" },
    code: [
      [c("pink","POST"), c("white"," your-server/hook")],
      [c("ink-dim","X-Signature: "), c("emerald","hmac256=…")],
      [c("ink-dim","{ "), c("sky","\"event\""), c("ink-dim",": "), c("amber","\"payment.paid\""), c("ink-dim"," }")],
    ],
  },
  { id: 8, act: 1, who: "paid",
    title: "Order paid",
    detail: "End-to-end, the customer just paid. You collect the receipt and ship.",
    badge: "LIVE CHARGE", elapsed: "1.84 s", kind: "RES",
    status: { code: "PAID", tone: "ok" },
    code: [
      [c("emerald","✓ payment.paid")],
      [c("ink-dim","amount:   "),  c("fuchsia","12.50 USD")],
      [c("ink-dim","latency:  "),  c("amber","1.84 s")],
    ],
  },
];

const COLOR_HEX: Record<Color, string> = {
  pink:     "#f472b6",
  "ink-dim":"#64748b",
  emerald:  "#34d399",
  sky:      "#7dd3fc",
  amber:    "#fcd34d",
  fuchsia:  "#f0abfc",
  violet:   "#c4b5fd",
  white:    "#e2e8f0",
};

/* ─────────────────── nodes per act ─────────────────── */

interface NodeDef {
  who: NodeId;
  icon: LucideIcon;
  label: string;
  /** Position on the 620 × 130 viewBox. */
  x: number;
  /** Color when active. */
  glow: string;
}

/** Act 1: 4 nodes laid out evenly. */
const ACT1: NodeDef[] = [
  { who: "you",    icon: UserIcon,   label: "You",          x:  70, glow: "#38bdf8" },
  { who: "signin", icon: Mail,       label: "Verify",       x: 233, glow: "#3a55ff" },
  { who: "link",   icon: ScanQrCode, label: "Link QR",      x: 397, glow: "#a855f7" },
  { who: "key",    icon: KeyRound,   label: "sk_ key",      x: 560, glow: "#34d399" },
];

/** Act 2: 5 nodes laid out evenly (last is "Paid"). */
const ACT2: NodeDef[] = [
  { who: "customer",icon: UserIcon,    label: "Customer", x:  70, glow: "#38bdf8" },
  { who: "api",     icon: Server,      label: "Byme Bank",x: 192, glow: "#3a55ff" },
  { who: "bank",    icon: Building2,   label: "Bakong",   x: 315, glow: "#a855f7" },
  { who: "webhook", icon: Webhook,     label: "Webhook",  x: 437, glow: "#fbbf24" },
  { who: "paid",    icon: Coins,       label: "Paid",     x: 560, glow: "#34d399" },
];

/* ─────────────────── component ─────────────────── */

export function PaymentFlow() {
  const [active, setActive] = useState(0);
  const [paused, setPaused] = useState(false);

  // Auto-loop, paused while user hovers the card.
  useEffect(() => {
    if (paused) return;
    const id = window.setInterval(() => {
      setActive((i) => (i + 1) % STEPS.length);
    }, STEP_MS);
    return () => window.clearInterval(id);
  }, [paused]);

  // Live wall-clock for the header chip.
  const [now, setNow] = useState<string>(() => formatClock(new Date()));
  useEffect(() => {
    const id = window.setInterval(() => setNow(formatClock(new Date())), 1000);
    return () => window.clearInterval(id);
  }, []);

  const current  = STEPS[active];
  const act      = current.act;
  const nodes    = act === 0 ? ACT1 : ACT2;
  const localIdx = nodes.findIndex((n) => n.who === current.who);

  // Type-out animation for the code panel.
  const totalChars = useMemo(
    () => current.code.reduce(
        (s, line) => s + line.reduce((s2, p) => s2 + p.t.length, 0), 0),
    [current],
  );
  const [typed, setTyped] = useState(0);
  useEffect(() => {
    setTyped(0);
    const t0 = performance.now();
    const dur = STEP_MS * 0.55;
    let raf = 0;
    const tick = () => {
      const p = Math.min(1, (performance.now() - t0) / dur);
      setTyped(Math.floor(totalChars * p));
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [active, totalChars]);

  // Per-act color tokens
  const actAccent = act === 0 ? "sky" : "emerald";

  return (
    <div
      className="relative"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      {/* Outer halo */}
      <div className="pointer-events-none absolute -inset-12 bg-gradient-to-br from-brand-500/30 via-accent-500/25 to-violet-500/30 rounded-[44px] blur-3xl"/>

      {/* Card */}
      <div className="relative rounded-3xl bg-gradient-to-br from-ink-900/95 to-ink-950/95 ring-1 ring-white/10 shadow-2xl overflow-hidden backdrop-blur-sm">
        {/* Top sheen line */}
        <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/40 to-transparent"/>
        {/* Top stripe — act-colored brand bar */}
        <motion.div
          className={`h-[3px] ${
            act === 0
              ? "bg-gradient-to-r from-sky-400 via-brand-500 to-sky-400"
              : "bg-gradient-to-r from-emerald-400 via-amber-400 to-emerald-400"
          }`}
          layout
          transition={{ duration: 0.4 }}
        />

        {/* Header */}
        <div className="flex items-center justify-between gap-3 px-6 py-3 border-b border-white/[.06]">
          <div className="flex items-center gap-2.5 min-w-0">
            <span className="relative flex h-2 w-2 shrink-0">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"/>
              <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-400"/>
            </span>
            <span className="text-[10px] uppercase tracking-[0.22em] font-extrabold text-emerald-300 truncate">
              live
            </span>
            <span className="h-3 w-px bg-white/15 mx-0.5"/>
            <span className="text-[10px] font-mono text-ink-400 tabular-nums tracking-tight">
              {now}
            </span>
          </div>
          <ActTabs
            act={act}
            onChange={(a) => setActive(STEPS.findIndex((s) => s.act === a))}
          />
        </div>

        {/* Diagram */}
        <div className="relative px-6 pt-7 pb-2">
          {/* Soft dotted grid behind the diagram */}
          <div
            className="pointer-events-none absolute inset-x-6 top-2 bottom-0 opacity-[.10]"
            style={{
              backgroundImage:
                "radial-gradient(rgba(255,255,255,.4) 1px, transparent 1px)",
              backgroundSize: "16px 16px",
            }}
          />
          <FlowDiagram nodes={nodes} active={localIdx} act={act}/>
        </div>

        {/* Caption */}
        <div className="px-6 pt-3 pb-4 min-h-[96px] relative">
          {/* left accent bar that flips with act */}
          <motion.span
            layout
            className={`absolute left-3 top-3 bottom-3 w-[3px] rounded-full ${
              act === 0
                ? "bg-gradient-to-b from-sky-400 to-brand-500"
                : "bg-gradient-to-b from-emerald-400 to-amber-400"
            }`}
          />
          <AnimatePresence mode="wait">
            <motion.div
              key={active}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.3 }}
            >
              <div className="flex items-center justify-between gap-3 flex-wrap">
                <div className="flex items-center gap-2 text-[10px] uppercase tracking-widest font-extrabold">
                  <span className="font-mono tabular-nums text-ink-500">
                    {String(active + 1).padStart(2, "0")}
                    <span className="text-ink-700 mx-0.5">/</span>
                    <span className="text-ink-500">{String(STEPS.length).padStart(2, "0")}</span>
                  </span>
                  <span className="h-3 w-px bg-white/10"/>
                  <span className={act === 0 ? "text-sky-300" : "text-emerald-300"}>
                    {current.badge}
                  </span>
                </div>
                <span className={`inline-flex items-center gap-1.5 text-[10px] font-mono tabular-nums tracking-tight whitespace-nowrap rounded-full px-2 py-0.5 ring-1 ${
                  act === 0
                    ? "bg-sky-500/10 text-sky-300 ring-sky-400/20"
                    : "bg-emerald-500/10 text-emerald-300 ring-emerald-400/20"
                }`}>
                  <Activity size={10}/>
                  {current.elapsed}
                </span>
              </div>
              <div className="mt-1.5 text-[20px] font-bold text-white tracking-tight leading-tight pl-3">
                {current.title}
              </div>
              <div className="text-[12.5px] text-ink-300 leading-snug mt-1 pl-3 max-w-[480px]">
                {current.detail}
              </div>
            </motion.div>
          </AnimatePresence>
          {/* helper hint */}
          <div className="absolute right-4 bottom-3 text-[9px] font-mono text-ink-600 hidden md:flex items-center gap-1">
            <Lock size={9}/>
            {paused ? "paused · move away to play" : "hover to pause"}
          </div>
          <span className="sr-only">{actAccent}</span>
        </div>

        {/* Code panel */}
        <div className="mx-6 mb-4 rounded-xl bg-ink-950/95 ring-1 ring-white/[.06] overflow-hidden shadow-inner">
          <div className="flex items-center gap-1.5 px-3 py-2 border-b border-white/[.05] bg-white/[.02]">
            <span className="h-2.5 w-2.5 rounded-full bg-rose-500/80"/>
            <span className="h-2.5 w-2.5 rounded-full bg-amber-500/80"/>
            <span className="h-2.5 w-2.5 rounded-full bg-emerald-500/80"/>
            <span className="ml-2 text-[10px] font-mono text-ink-400 truncate">
              {fileName(current)}
            </span>
            <KindBadge kind={current.kind}/>
            <span className="ml-auto flex items-center gap-2">
              <StatusChip code={current.status.code} tone={current.status.tone}/>
              <span className="text-[10px] font-mono text-ink-500 tabular-nums">
                {totalChars > 0
                  ? `${Math.min(100, Math.round((typed / totalChars) * 100))}%`
                  : "—"}
              </span>
            </span>
          </div>
          <pre className="px-2 py-3 text-[11.5px] font-mono leading-[1.6] min-h-[114px] grid grid-cols-[28px_1fr]">
            {/* line numbers gutter */}
            <span className="text-right pr-2 text-ink-700 select-none">
              {current.code.map((_, i) => (
                <div key={i}>{String(i + 1).padStart(2, "0")}</div>
              ))}
            </span>
            <span>
              {renderTyped(current.code, typed)}
              <span className="caret"/>
            </span>
          </pre>
          {/* mini stats row */}
          <div className="flex items-center gap-3 px-3 py-1.5 border-t border-white/[.05] bg-white/[.015] text-[10px] font-mono text-ink-500 tabular-nums">
            <span className="text-emerald-400/80">●</span>
            <span>region <span className="text-ink-300">ap-southeast-1</span></span>
            <span className="text-ink-700">·</span>
            <span>tls <span className="text-ink-300">1.3</span></span>
            <span className="text-ink-700">·</span>
            <span>idem <span className="text-ink-300">{idemKey(current)}</span></span>
          </div>
        </div>

        {/* Step scrubber — clickable bars */}
        <div className="px-6 pb-4">
          <Scrubber active={active} onJump={setActive}/>
        </div>

        {/* Footer progress bar */}
        <div className="relative h-[3px] bg-ink-900/80 flex">
          {([0, 1] as Act[]).map((a) => {
            const total = STEPS.filter((s) => s.act === a).length;
            const done  = STEPS.filter((s) => s.act === a && s.id <= active).length;
            const pct   = (done / total) * 100;
            return (
              <div key={a} className="flex-1 relative">
                <motion.div
                  className={`absolute inset-y-0 left-0 ${
                    a === 0
                      ? "bg-gradient-to-r from-sky-500 to-brand-500"
                      : "bg-gradient-to-r from-violet-500 via-amber-400 to-emerald-400"
                  }`}
                  initial={{ width: "0%" }}
                  animate={{ width: `${pct}%` }}
                  transition={{ duration: 0.6, ease: "easeOut" }}
                />
                {a === 0 && <span className="absolute right-0 inset-y-0 w-px bg-white/10"/>}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/* ─────────────────── act tabs ─────────────────── */

function ActTabs({ act, onChange }: { act: Act; onChange: (a: Act) => void }) {
  return (
    <div className="relative flex items-center gap-1 rounded-full bg-white/5 p-1 ring-1 ring-white/10 text-[10px] font-extrabold shrink-0">
      <motion.span
        className={`absolute top-1 bottom-1 rounded-full transition-colors ${
          act === 0
            ? "bg-sky-500/20 ring-1 ring-sky-400/40"
            : "bg-emerald-500/20 ring-1 ring-emerald-400/40"
        }`}
        animate={{
          left:  act === 0 ? 4 : "calc(50% + 2px)",
          right: act === 0 ? "calc(50% + 2px)" : 4,
        }}
        transition={{ type: "spring", stiffness: 360, damping: 28 }}
      />
      <button
        onClick={() => onChange(0)}
        className={`relative z-10 px-2.5 py-1 transition ${
          act === 0 ? "text-sky-200" : "text-ink-500 hover:text-ink-300"
        }`}
      >
        ACT 1 · ONBOARD
      </button>
      <ArrowRight size={9} className="text-ink-600 relative z-10 mx-0.5"/>
      <button
        onClick={() => onChange(1)}
        className={`relative z-10 px-2.5 py-1 transition ${
          act === 1 ? "text-emerald-200" : "text-ink-500 hover:text-ink-300"
        }`}
      >
        ACT 2 · CHARGE
      </button>
    </div>
  );
}

/* ─────────────────── code-panel chips ─────────────────── */

function KindBadge({ kind }: { kind: Step["kind"] }) {
  const cfg = {
    REQ: { bg: "bg-pink-500/15",    text: "text-pink-300",    ring: "ring-pink-400/30",    label: "REQ" },
    RES: { bg: "bg-emerald-500/15", text: "text-emerald-300", ring: "ring-emerald-400/30", label: "RES" },
    EVT: { bg: "bg-violet-500/15",  text: "text-violet-300",  ring: "ring-violet-400/30",  label: "EVT" },
  }[kind];
  return (
    <span className={`ml-1.5 text-[9px] font-extrabold tracking-wider rounded px-1.5 py-0.5 ring-1 ${cfg.bg} ${cfg.text} ${cfg.ring}`}>
      {cfg.label}
    </span>
  );
}

function StatusChip({ code, tone }: { code: string; tone: "ok" | "warn" | "info" }) {
  const cfg = {
    ok:   { bg: "bg-emerald-500/15", text: "text-emerald-300", ring: "ring-emerald-400/30" },
    warn: { bg: "bg-amber-500/15",   text: "text-amber-300",   ring: "ring-amber-400/30" },
    info: { bg: "bg-sky-500/15",     text: "text-sky-300",     ring: "ring-sky-400/30" },
  }[tone];
  return (
    <span className={`text-[9px] font-mono font-bold tracking-tight rounded-full px-2 py-0.5 ring-1 ${cfg.bg} ${cfg.text} ${cfg.ring}`}>
      {code}
    </span>
  );
}

/* ─────────────────── scrubber bars ─────────────────── */

function Scrubber({ active, onJump }: { active: number; onJump: (i: number) => void }) {
  return (
    <div className="flex items-center gap-1.5">
      {STEPS.map((s, i) => {
        const isActive = i === active;
        const isDone   = i <  active;
        const isAct0   = s.act === 0;
        return (
          <button
            key={s.id}
            type="button"
            onClick={() => onJump(i)}
            aria-label={`Jump to step ${i + 1}: ${s.title}`}
            className="group relative flex-1 h-1.5 rounded-full overflow-hidden bg-white/5 hover:bg-white/10 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-white/30"
          >
            <span
              className={`absolute inset-y-0 left-0 transition-all duration-500 ${
                isActive
                  ? `w-full ${isAct0 ? "bg-sky-400" : "bg-emerald-400"} shadow-[0_0_10px_rgba(56,189,248,.6)]`
                  : isDone
                    ? `w-full ${isAct0 ? "bg-sky-500/50" : "bg-emerald-500/50"}`
                    : "w-0"
              }`}
            />
          </button>
        );
      })}
    </div>
  );
}

/* ─────────────────── helpers ─────────────────── */

function formatClock(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function idemKey(step: Step): string {
  // Deterministic mock idempotency key per step so it doesn't jitter.
  const seed = step.id * 9301 + 49297;
  const hex = (Math.abs(Math.sin(seed)) * 0xffffff | 0).toString(16).padStart(6, "0");
  return `${hex.slice(0, 4)}…${hex.slice(2, 6)}`;
}

function fileName(step: Step): string {
  switch (step.who) {
    case "you":      return "auth.email.request.http";
    case "signin":   return "auth.email.verify.http";
    case "link":     return "merchants.upload.http";
    case "key":      return "upstream-key.refresh.http";
    case "customer": return "scan.req.http";
    case "api":      return "payments.qr.http";
    case "bank":     return "bakong.settle.log";
    case "webhook":  return "webhook.payment-paid.http";
    case "paid":     return "result.json";
  }
}

function renderTyped(lines: ColoredLine[][], typed: number) {
  let used = 0;
  return lines.map((line, i) => {
    const parts: React.ReactNode[] = [];
    line.forEach((p, j) => {
      const remain = typed - used;
      if (remain <= 0) return;
      const text = p.t.slice(0, Math.max(0, remain));
      used += text.length;
      parts.push(
        <span key={j} style={{ color: COLOR_HEX[p.c] }}>{text}</span>,
      );
    });
    return <div key={i}>{parts.length === 0 ? "\u00a0" : parts}</div>;
  });
}

/* ─────────────────── diagram ─────────────────── */

function curvePath(ax: number, bx: number, y = 60, lift = 22) {
  const cx1 = ax + (bx - ax) * 0.30;
  const cx2 = ax + (bx - ax) * 0.70;
  const cy  = y - lift;
  return `M ${ax + 26},${y} C ${cx1},${cy} ${cx2},${cy} ${bx - 26},${y}`;
}

function FlowDiagram({ nodes, active, act }: { nodes: NodeDef[]; active: number; act: Act }) {
  return (
    <div className="relative">
      <svg viewBox="0 0 620 130" className="w-full" preserveAspectRatio="xMidYMid meet">
        <defs>
          <linearGradient id="wireGradActive" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%"   stopColor="#3a55ff" stopOpacity=".75"/>
            <stop offset="50%"  stopColor="#0ecf81" stopOpacity=".95"/>
            <stop offset="100%" stopColor="#a855f7" stopOpacity=".75"/>
          </linearGradient>
          <linearGradient id="wireGradIdle" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%"   stopColor="#3a55ff" stopOpacity=".15"/>
            <stop offset="100%" stopColor="#a855f7" stopOpacity=".15"/>
          </linearGradient>
          <radialGradient id="packetGrad">
            <stop offset="0%"   stopColor="#ffffff" stopOpacity="1"/>
            <stop offset="55%"  stopColor="#0ecf81" stopOpacity=".95"/>
            <stop offset="100%" stopColor="#0ecf81" stopOpacity="0"/>
          </radialGradient>
          {/* Per-node radial highlight gradients (top-left light source) */}
          {nodes.map((n) => (
            <radialGradient key={`fill-${n.who}`} id={`nodeFill-${n.who}`} cx="30%" cy="25%" r="80%">
              <stop offset="0%"  stopColor="#ffffff" stopOpacity=".55"/>
              <stop offset="50%" stopColor={n.glow}  stopOpacity="1"/>
              <stop offset="100%" stopColor={n.glow} stopOpacity=".75"/>
            </radialGradient>
          ))}
          <filter id="nodeGlow" x="-80%" y="-80%" width="260%" height="260%">
            <feGaussianBlur stdDeviation="7" result="blur"/>
            <feMerge>
              <feMergeNode in="blur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
        </defs>

        {/* Wires */}
        {nodes.slice(0, -1).map((n, i) => {
          const next = nodes[i + 1];
          const pathId = `wire-${act}-${i}`;
          const flowing = active >= i;
          return (
            <g key={i}>
              {/* idle dashed base wire — always visible */}
              <path
                d={curvePath(n.x, next.x)}
                fill="none"
                stroke="url(#wireGradIdle)"
                strokeWidth={1.25}
                strokeDasharray="3 4"
              />
              {/* active solid wire + light packets */}
              {flowing && (
                <>
                  <path
                    id={pathId}
                    d={curvePath(n.x, next.x)}
                    fill="none"
                    stroke="url(#wireGradActive)"
                    strokeWidth={1.75}
                  />
                  <circle r={5} fill="url(#packetGrad)">
                    <animateMotion dur="1.8s" repeatCount="indefinite"
                                   keyPoints="0;1" keyTimes="0;1"
                                   calcMode="linear">
                      <mpath xlinkHref={`#${pathId}`}/>
                    </animateMotion>
                  </circle>
                  <circle r={3.2} fill="url(#packetGrad)" opacity=".7">
                    <animateMotion dur="1.8s" begin="0.75s" repeatCount="indefinite"
                                   keyPoints="0;1" keyTimes="0;1"
                                   calcMode="linear">
                      <mpath xlinkHref={`#${pathId}`}/>
                    </animateMotion>
                  </circle>
                </>
              )}
            </g>
          );
        })}

        {/* Nodes */}
        {nodes.map((n, i) => {
          const isActive = active === i;
          const isDone   = active >  i;
          return (
            <g key={i} transform={`translate(${n.x},60)`}>
              {isActive && (
                <>
                  <motion.circle
                    r={24} fill="none" stroke={n.glow} strokeWidth={2}
                    initial={{ r: 24, opacity: 1 }}
                    animate={{ r: [24, 52, 24], opacity: [.85, 0, .85] }}
                    transition={{ duration: 2, repeat: Infinity, ease: "easeOut" }}
                  />
                  <motion.circle
                    r={24} fill="none" stroke={n.glow} strokeWidth={1.5}
                    initial={{ r: 24, opacity: .6 }}
                    animate={{ r: [24, 40, 24], opacity: [.6, 0, .6] }}
                    transition={{ duration: 2, repeat: Infinity, ease: "easeOut", delay: 0.4 }}
                  />
                </>
              )}
              {/* Outer subtle ring */}
              <circle r={26} fill="none" stroke="rgba(255,255,255,.06)" strokeWidth={1}/>
              {/* Main filled disc — gradient when active for that 3D feel */}
              <circle
                r={22}
                fill={
                  isActive
                    ? `url(#nodeFill-${n.who})`
                    : isDone   ? "#0a563b"
                               : "#1a2236"
                }
                stroke={isActive ? n.glow : isDone ? "#0ecf81" : "#3c4453"}
                strokeWidth={2}
                filter={isActive ? "url(#nodeGlow)" : undefined}
              />
              {/* Top highlight rim for the bevel */}
              {isActive && (
                <ellipse
                  cx={0} cy={-9} rx={11} ry={4}
                  fill="rgba(255,255,255,.35)"
                />
              )}
              <foreignObject x={-11} y={-11} width={22} height={22}>
                <div className="grid place-items-center w-full h-full text-white">
                  {isDone
                    ? <CircleCheckBig size={15}/>
                    : <n.icon size={15}/>}
                </div>
              </foreignObject>
              <text
                y={48}
                textAnchor="middle"
                fill={isActive ? "#ffffff" : isDone ? "#86efac" : "#94a3b8"}
                style={{ font: "600 11px system-ui, sans-serif", letterSpacing: ".01em" }}
              >
                {n.label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
