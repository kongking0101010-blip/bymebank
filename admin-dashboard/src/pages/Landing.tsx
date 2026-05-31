import { Link, useNavigate } from "react-router-dom";
import { motion, useScroll, useTransform } from "framer-motion";
import { useRef, useState } from "react";
import {
  ShieldCheck, Zap, Globe2, ArrowRight, ScanQrCode, Webhook, Lock,
  LayoutDashboard, LogOut, Sparkles, CheckCircle2, Code2, Github,
  TrendingUp, Coins, MousePointerClick,
} from "lucide-react";

import { Logo } from "@/components/Logo";
import { PrimaryCTA } from "@/components/PrimaryCTA";
import { ScrollProgress } from "@/components/ScrollProgress";
import { Counter } from "@/components/Counter";
import { VantaGlobe } from "@/components/VantaGlobe";
import { PaymentFlow } from "@/components/PaymentFlow";
import { HowItWorksFlow } from "@/components/HowItWorksFlow";
import { FeatureCard } from "@/components/FeatureCard";
import { FinalCTA } from "@/components/FinalCTA";
import { useAuthStore } from "@/store/auth";

/* ─── data ───────────────────────────────────────────── */

const FEATURES = [
  { icon: ScanQrCode, title: "Universal KHQR",       text: "One QR works across ABA, Wing, Bakong & every KHQR-enabled wallet.",
    metric: "4 BANKS", span: "lg:col-span-2" },
  { icon: Zap,        title: "Sub-200 ms latency",   text: "Generate compliant EMV QR codes in under 200 ms with our Java/Python SDK.",
    metric: "P95 · 184 ms", span: "" },
  { icon: Webhook,    title: "Realtime webhooks",    text: "Be notified the moment a customer pays — every event signed with HMAC.",
    metric: "HMAC · SHA-256", span: "" },
  { icon: ShieldCheck,title: "Bank-grade security",  text: "AES-256 at rest, TLS 1.3 in transit, scoped API keys, and a full audit log.",
    metric: "SOC-2 · READY", span: "lg:col-span-2" },
  { icon: Lock,       title: "Quotas & rate-limits", text: "Per-key limits, plan quotas, and IP allow-lists keep abuse out.",
    metric: "PER-KEY", span: "" },
  { icon: Globe2,     title: "Java + Python SDKs",   text: "Drop-in clients with typed responses, retries, and webhook helpers.",
    metric: "2 LANGUAGES", span: "lg:col-span-2" },
];

const BANK_LOGOS = ["ABA Bank", "ACLEDA", "Wing", "Bakong", "AMK", "Chip Mong", "PRASAC", "FTB"];

const FAQ = [
  { q: "How do I get an API key?",
    a: "Sign in with Google or your Gmail (OTP). The dashboard walks you through linking your bank QR codes, then mints a real sk_ key in seconds." },
  { q: "Which banks do you support?",
    a: "ABA, ACLEDA, Wing, and Bakong directly — plus any wallet that scans KHQR." },
  { q: "Is there a fee?",
    a: "Free during launch. No credit card needed, no per-transaction fee until your account is approved for paid plans." },
  { q: "What about security?",
    a: "Every key is hashed (SHA-256) at rest, mer­chant secrets are AES-256-GCM encrypted, and every action is recorded in an audit log you can read." },
];

/* ─── helpers ───────────────────────────────────────── */

/** A single FAQ row — minimal, hairline-divided, with a clean
 *  height-animated body. The trigger is a button (a11y-correct) that
 *  flips a thin plus glyph into a minus instead of rotating it. */
function FaqRow({ q, a }: { q: string; a: string }) {
  const [open, setOpen] = useState(false);
  return (
    <li>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="group w-full flex items-start justify-between gap-6 py-6 text-left"
      >
        <span className={`text-[16px] sm:text-[17px] font-medium tracking-tight transition-colors ${
          open ? "text-ink-900" : "text-ink-700 group-hover:text-ink-900"
        }`}>
          {q}
        </span>
        {/* Animated plus → minus glyph (two thin strokes) */}
        <span className="relative shrink-0 mt-1 h-4 w-4 text-ink-400 group-hover:text-ink-700 transition-colors">
          <span className="absolute inset-x-0 top-1/2 h-px -translate-y-1/2 bg-current"/>
          <motion.span
            className="absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-current"
            animate={{ scaleY: open ? 0 : 1, opacity: open ? 0 : 1 }}
            transition={{ duration: 0.2, ease: "easeOut" }}
          />
        </span>
      </button>
      <motion.div
        initial={false}
        animate={{ height: open ? "auto" : 0, opacity: open ? 1 : 0 }}
        transition={{ duration: 0.25, ease: [0.21, 0.61, 0.35, 1] }}
        className="overflow-hidden"
      >
        <p className="pb-6 pr-12 text-[14.5px] leading-[1.65] text-ink-600 max-w-2xl">
          {a}
        </p>
      </motion.div>
    </li>
  );
}

function TiltCard({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement | null>(null);
  function move(e: React.MouseEvent<HTMLDivElement>) {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const px = (e.clientX - r.left) / r.width;
    const py = (e.clientY - r.top) / r.height;
    const rx = (py - 0.5) * -8;   // tilt back when cursor near top
    const ry = (px - 0.5) *  8;
    el.style.transform = `perspective(1000px) rotateX(${rx}deg) rotateY(${ry}deg)`;
    el.style.setProperty("--mx", `${e.clientX - r.left}px`);
    el.style.setProperty("--my", `${e.clientY - r.top}px`);
  }
  function leave() { if (ref.current) ref.current.style.transform = ""; }
  return (
    <div ref={ref} onMouseMove={move} onMouseLeave={leave}
         className={`tilt ${className}`}>
      {children}
    </div>
  );
}

/* ─── main ──────────────────────────────────────────── */

export default function Landing() {
  const user   = useAuthStore((s) => s.user);
  const token  = useAuthStore((s) => s.token);
  const logout = useAuthStore((s) => s.logout);
  const nav    = useNavigate();
  const signedIn = !!token && !!user;

  // Subtle parallax on the hero floating card
  const heroRef = useRef<HTMLDivElement | null>(null);
  const { scrollYProgress } = useScroll({
    target: heroRef, offset: ["start start", "end start"],
  });
  const yFloat = useTransform(scrollYProgress, [0, 1], [0, -60]);

  function signOut() { logout(); nav("/"); }

  return (
    <div className="min-h-screen bg-white text-ink-900">
      <ScrollProgress />

      {/* Top nav */}
      <header className="sticky top-0 z-30 glass border-b border-ink-100">
        <div className="max-w-7xl mx-auto px-6 py-3 flex items-center">
          <Logo />
          <nav className="ml-10 hidden md:flex items-center gap-6 text-sm font-medium text-ink-600">
            <a href="#features" className="hover:text-ink-900 transition">Features</a>
            <a href="#how-it-works" className="hover:text-ink-900 transition">How it works</a>
            <a href="#banks"    className="hover:text-ink-900 transition">Banks</a>
            <a href="#faq"      className="hover:text-ink-900 transition">FAQ</a>
          </nav>
          <div className="ml-auto flex items-center gap-2">
            {signedIn ? (
              <>
                <div className="hidden sm:flex items-center gap-2 rounded-full bg-ink-50 ring-1 ring-ink-100 pl-3 pr-1.5 py-1.5">
                  <div>
                    <div className="text-xs font-semibold text-ink-800 leading-tight">{user!.fullName}</div>
                    <div className="text-[10px] text-ink-500 leading-tight">{user!.email}</div>
                  </div>
                  {user!.avatarUrl
                    ? <img src={user!.avatarUrl} alt=""
                           className="h-7 w-7 rounded-full object-cover"/>
                    : <div className="h-7 w-7 rounded-full bg-gradient-to-br from-brand-500 to-brand-800 text-white grid place-items-center text-[11px] font-bold">
                        {user!.fullName?.charAt(0) ?? "U"}
                      </div>}
                </div>
                <button onClick={signOut} title="Sign out" className="btn-ghost rounded-full p-2">
                  <LogOut size={18}/>
                </button>
                <PrimaryCTA to="/app" variant="compact">
                  Go to dashboard
                </PrimaryCTA>
              </>
            ) : (
              <PrimaryCTA to="/login" variant="compact">
                Sign in
              </PrimaryCTA>
            )}
          </div>
        </div>
      </header>

      {/* Hero */}
      <section ref={heroRef} className="relative overflow-hidden bg-[#11154d]">
        {/* Vanta animated globe — sits behind everything */}
        <VantaGlobe className="absolute inset-0 z-0"/>
        {/* Subtle dark wash on top so foreground text stays readable */}
        <div className="absolute inset-0 z-[1] bg-gradient-to-b from-[#11154d]/40 via-transparent to-[#11154d]/80"/>
        <div className="absolute inset-0 z-[1] dotted-grid opacity-[0.10]"/>

        <div className="relative z-10 max-w-7xl mx-auto px-6 py-24 lg:py-36 text-white">
          <motion.div initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} className="max-w-3xl relative z-10">
            <span className="inline-flex items-center gap-2 rounded-full bg-white/10 px-3 py-1 text-xs font-semibold ring-1 ring-white/20 backdrop-blur">
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-300 opacity-75"/>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-accent-300"/>
              </span>
              Now live for ABA, ACLEDA, Wing &amp; Bakong
            </span>

            <h1 className="mt-6 text-5xl sm:text-6xl lg:text-[5.25rem] font-semibold tracking-[-0.045em] leading-[1.02] text-balance drop-shadow-[0_2px_24px_rgba(11,9,57,0.6)]">
              One API.
              <br/>
              <span className="bg-gradient-to-r from-white via-accent-200 to-white bg-clip-text text-transparent">
                Every Cambodian bank.
              </span>
            </h1>

            <p className="mt-6 text-[17px] text-white/80 max-w-xl leading-[1.55] tracking-[-0.005em]">
              Byme Bank is the modern payment gateway for Cambodia.
              Generate KHQR codes, verify payments, and ship products
              faster — with Java and Python SDKs you'll actually enjoy.
            </p>

            <div className="mt-8 flex flex-wrap items-center gap-3">
              {signedIn ? (
                <>
                  <Link to="/app"
                        className="group relative inline-flex items-center gap-3 rounded-2xl bg-white text-brand-700 pl-2 pr-5 py-2 hover:bg-accent-50 transition shadow-glow">
                    <span className="relative">
                      {user!.avatarUrl
                        ? <img src={user!.avatarUrl} alt=""
                               className="h-10 w-10 rounded-full object-cover ring-2 ring-white"/>
                        : <span className="block h-10 w-10 rounded-full bg-gradient-to-br from-brand-500 to-brand-800 text-white grid place-items-center text-base font-bold ring-2 ring-white">
                            {user!.fullName?.charAt(0) ?? "U"}
                          </span>}
                      <span className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full bg-accent-500 ring-2 ring-white animate-pulseRing"/>
                    </span>
                    <span className="text-left">
                      <span className="block text-[10px] uppercase tracking-widest text-brand-500 font-bold">
                        Signed in as
                      </span>
                      <span className="block font-bold leading-tight">{user!.fullName}</span>
                      <span className="block text-[11px] text-ink-500 leading-tight">{user!.email}</span>
                    </span>
                    <span className="ml-3 inline-flex items-center gap-1 font-bold">
                      <LayoutDashboard size={14}/> Open
                      <ArrowRight size={14} className="transition group-hover:translate-x-0.5"/>
                    </span>
                  </Link>
                  <button onClick={signOut} className="btn-secondary !bg-white/10 !text-white !ring-white/20 hover:!bg-white/20">
                    <LogOut size={14}/> Sign out
                  </button>
                </>
              ) : (
                <>
                  <Link to="/login"
                        className="inline-flex items-center gap-2 rounded-xl bg-white px-5 py-3
                                   text-sm font-bold text-brand-700 shadow-glow ring-2 ring-white/40
                                   hover:bg-accent-50 hover:text-brand-800 transition">
                    Get your API key <ArrowRight size={14}/>
                  </Link>
                  <a href="#features"
                     className="inline-flex items-center gap-2 rounded-xl bg-white/10 ring-1 ring-white/20
                                text-white px-5 py-3 text-sm font-semibold backdrop-blur
                                hover:bg-white/20 transition">
                    See features
                  </a>
                </>
              )}
            </div>

            {/* Counter strip */}
            <div className="mt-12 grid grid-cols-3 gap-3 max-w-md">
              <div className="rounded-2xl bg-white/10 backdrop-blur-sm p-4 ring-1 ring-white/15">
                <div className="text-3xl font-extrabold">
                  <Counter to={4}/>
                </div>
                <div className="text-[10px] uppercase tracking-wider opacity-75 mt-1">Banks</div>
                <div className="text-[10px] opacity-60 mt-0.5">all major</div>
              </div>
              <div className="rounded-2xl bg-white/10 backdrop-blur-sm p-4 ring-1 ring-white/15">
                <div className="text-3xl font-extrabold">
                  &lt;<Counter to={200}/>ms
                </div>
                <div className="text-[10px] uppercase tracking-wider opacity-75 mt-1">Latency</div>
                <div className="text-[10px] opacity-60 mt-0.5">QR mint</div>
              </div>
              <div className="rounded-2xl bg-white/10 backdrop-blur-sm p-4 ring-1 ring-white/15">
                <div className="text-3xl font-extrabold">
                  <Counter to={99.9} decimals={1}/>%
                </div>
                <div className="text-[10px] uppercase tracking-wider opacity-75 mt-1">Uptime</div>
                <div className="text-[10px] opacity-60 mt-0.5">SLA</div>
              </div>
            </div>
          </motion.div>

          {/* Hero floating flow video */}
          <motion.div
            style={{ y: yFloat }}
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.2 }}
            className="hidden lg:block absolute right-8 top-48 xl:top-56 w-[520px] xl:w-[540px]"
          >
            <TiltCard className="relative">
              <PaymentFlow />
            </TiltCard>
          </motion.div>
        </div>
      </section>

      {/* Banks marquee */}
      <section id="banks" className="py-12 border-y border-ink-100 bg-white">
        <p className="text-center text-xs font-semibold text-ink-500 uppercase tracking-widest">
          Connected to Cambodia's leading banks
        </p>
        <div className="marquee mt-6">
          <div className="marquee-track">
            {[...BANK_LOGOS, ...BANK_LOGOS].map((name, i) => (
              <span key={i}
                    className="text-2xl font-extrabold text-ink-300 hover:text-ink-700 transition shrink-0">
                {name}
              </span>
            ))}
          </div>
        </div>
      </section>

      {/* Features — bento grid */}
      <section id="features" className="py-28 bg-white">
        <div className="max-w-7xl mx-auto px-6">
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.5, ease: [0.21, 0.61, 0.35, 1] }}
            className="max-w-2xl"
          >
            <div className="text-[11px] uppercase tracking-[0.18em] font-semibold text-ink-500">
              Features
            </div>
            <h2 className="mt-3 text-3xl sm:text-5xl font-semibold tracking-tight leading-[1.05] text-ink-900">
              Everything you need.
              <br/>
              <span className="text-ink-400">Nothing you don't.</span>
            </h2>
            <p className="mt-4 text-ink-500 text-[15px] sm:text-base leading-relaxed max-w-xl">
              We handle KHQR, signing, retries, and reconciliation so you can focus on building the product.
            </p>
          </motion.div>

          <div className="mt-12 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {FEATURES.map((f, i) => (
              <FeatureCard
                key={f.title}
                icon={f.icon}
                title={f.title}
                text={f.text}
                metric={f.metric}
                span={f.span}
                index={i}
              />
            ))}
          </div>
        </div>
      </section>

      {/* How it works */}
      <section id="how-it-works" className="py-24 bg-white">
        <div className="max-w-7xl mx-auto px-6">
          <div className="max-w-2xl">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-accent-50 px-3 py-1 text-xs font-bold uppercase tracking-widest text-accent-700 ring-1 ring-accent-200/60">
              <Code2 size={12}/> Workflow
            </span>
            <h2 className="mt-4 text-4xl sm:text-5xl font-extrabold tracking-tight">
              Live in three steps.
            </h2>
            <p className="mt-4 text-ink-500 text-lg">
              Sign in, link your bank, mint a key. The whole flow takes under 60 seconds.
            </p>
          </div>

          <div className="mt-14">
            <HowItWorksFlow/>
          </div>
        </div>
      </section>

      {/* FAQ */}
      <section id="faq" className="py-28 bg-white border-t border-ink-100">
        <div className="max-w-6xl mx-auto px-6">
          <div className="grid lg:grid-cols-[1fr_2fr] gap-12 lg:gap-20 items-start">
            {/* Left rail: eyebrow + headline + supporting copy */}
            <div className="lg:sticky lg:top-28">
              <div className="text-[11px] uppercase tracking-[0.18em] font-semibold text-ink-500">
                FAQ
              </div>
              <h2 className="mt-3 text-3xl sm:text-4xl font-semibold tracking-tight text-ink-900 leading-[1.1]">
                Frequently asked questions
              </h2>
              <p className="mt-4 text-[15px] text-ink-500 leading-relaxed max-w-xs">
                Quick answers to the things people ask most. Need something else?{" "}
                <a href="mailto:hello@bymebank.dev"
                   className="font-medium text-ink-900 underline underline-offset-4 decoration-ink-300 hover:decoration-ink-900 transition">
                  Email us
                </a>.
              </p>
            </div>

            {/* Right column: hairline-divided list of questions */}
            <ul className="divide-y divide-ink-100 border-y border-ink-100">
              {FAQ.map((f, i) => (
                <FaqRow key={i} q={f.q} a={f.a}/>
              ))}
            </ul>
          </div>
        </div>
      </section>

      {/* Final CTA — animated, type-led */}
      <FinalCTA
        signedIn={signedIn}
        firstName={signedIn ? user!.fullName?.split(" ")[0] ?? null : null}
      />

      <footer className="py-10 border-t border-ink-100 bg-white">
        <div className="max-w-7xl mx-auto px-6 flex flex-col md:flex-row items-center gap-4 text-sm text-ink-500">
          <Logo />
          <div className="md:ml-auto flex items-center gap-5 text-xs">
            <a href="#features" className="hover:text-ink-900">Features</a>
            <a href="#how-it-works" className="hover:text-ink-900">How it works</a>
            <a href="#faq" className="hover:text-ink-900">FAQ</a>
            <a href="https://github.com/" target="_blank" rel="noopener noreferrer"
               className="hover:text-ink-900 inline-flex items-center gap-1">
              <Github size={12}/> GitHub
            </a>
          </div>
          <p className="text-xs text-ink-400 md:order-last">
            © {new Date().getFullYear()} Byme Bank · Built in Phnom Penh.
          </p>
        </div>
      </footer>

      {/* Hidden import marker so unused imports don't get tree-shaken away by mistake */}
      <span className="sr-only"><Coins/></span>
    </div>
  );
}
