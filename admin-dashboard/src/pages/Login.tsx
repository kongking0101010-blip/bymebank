import { FormEvent, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { motion, AnimatePresence } from "framer-motion";
import toast from "react-hot-toast";
import {
  Mail, Loader2, KeyRound, Lock, ArrowLeft, ArrowRight, ShieldCheck,
  Eye, EyeOff, Check,
} from "lucide-react";

import { Logo } from "@/components/Logo";
import { VantaGlobe } from "@/components/VantaGlobe";
import { apiPost, ApiError } from "@/api/client";
import { useAuthStore } from "@/store/auth";

interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: {
    id: string; email: string; fullName: string; role: string;
    status?: string; avatarUrl?: string | null;
  };
}

interface Lookup {
  exists: boolean;
  hasPassword: boolean;
  fullName?: string;
  avatarUrl?: string | null;
}

declare global { interface Window { google?: any } }

const GOOGLE_CLIENT_ID = (import.meta.env.VITE_GOOGLE_CLIENT_ID as string) || "";

type Step =
  | { kind: "email" }
  | { kind: "password"; email: string; existing: Lookup }
  | { kind: "otp_request"; email: string }
  | { kind: "otp_verify"; email: string; isNew: boolean }
  | { kind: "otp_set_password"; email: string; auth: AuthResponse };

export default function Login() {
  const nav = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);

  const [step, setStep] = useState<Step>({ kind: "email" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPwd, setShowPwd] = useState(false);
  const [code, setCode] = useState("");
  const [resendIn, setResendIn] = useState(0);
  const [loading, setLoading] = useState(false);

  const [newPwd, setNewPwd]   = useState("");
  const [newPwd2, setNewPwd2] = useState("");

  const googleBtnRef = useRef<HTMLDivElement>(null);

  /* ── Google ID-token sign-in ───────────────────────────────── */
  useEffect(() => {
    if (step.kind !== "email" || !GOOGLE_CLIENT_ID || !googleBtnRef.current) return;
    function render() {
      try {
        window.google!.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          callback: onGoogleCredential,
        });
        window.google!.accounts.id.renderButton(googleBtnRef.current!, {
          theme: "outline", size: "large", width: 360,
          text: "continue_with", shape: "rectangular",
        });
      } catch (e) { console.warn("google init failed", e); }
    }
    if (window.google) { render(); return; }
    const s = document.createElement("script");
    s.src = "https://accounts.google.com/gsi/client";
    s.async = true; s.defer = true; s.onload = render;
    document.head.appendChild(s);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step.kind]);

  async function onGoogleCredential(resp: { credential: string }) {
    setLoading(true);
    try {
      const r = await apiPost<AuthResponse>("/auth/google", { idToken: resp.credential });
      finishSignIn(r);
    } catch (e) {
      toast.error((e as ApiError).message ?? "Google sign-in failed");
    } finally { setLoading(false); }
  }

  /* ── Step 1: continue with email — pick path based on lookup ── */
  async function continueWithEmail(e: FormEvent) {
    e.preventDefault();
    if (!email) { toast.error("Email required"); return; }
    setLoading(true);
    try {
      const lookup = await apiPost<Lookup>("/auth/lookup", { email });

      if (lookup.exists && lookup.hasPassword) {
        // Has account with password → show password field
        setStep({ kind: "password", email, existing: lookup });
      } else {
        // Either new user or existing-without-password → OTP path
        await apiPost("/auth/email/request", { email });
        toast.success("Code sent — check your Gmail");
        setStep({ kind: "otp_verify", email, isNew: !lookup.exists });
        setResendIn(30);
        setCode("");
      }
    } catch (err) {
      toast.error((err as ApiError).message ?? "Could not continue");
    } finally { setLoading(false); }
  }

  /* ── Password login ───────────────────────────────────────── */
  async function passwordLogin(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    try {
      const r = await apiPost<AuthResponse>("/api/v1/auth/login", { email, password });
      finishSignIn(r);
    } catch (err) {
      toast.error((err as ApiError).message ?? "Login failed");
    } finally { setLoading(false); }
  }

  /* ── OTP verify ───────────────────────────────────────────── */
  async function verifyCode(e?: FormEvent) {
    e?.preventDefault();
    if (code.length !== 6) { toast.error("Enter the 6-digit code"); return; }
    setLoading(true);
    try {
      const r = await apiPost<AuthResponse>("/auth/email/verify", { email, code });
      // Token in store immediately so /me/password works
      setAuth(r.accessToken, r.refreshToken, {
        id: r.user.id, email: r.user.email, fullName: r.user.fullName,
        role: r.user.role, status: r.user.status, avatarUrl: r.user.avatarUrl,
      });
      // Only NEW users get prompted to set a password.
      if (step.kind === "otp_verify" && step.isNew) {
        setStep({ kind: "otp_set_password", email, auth: r });
        setNewPwd(""); setNewPwd2("");
      } else {
        finishSignIn(r);
      }
    } catch (err) {
      toast.error((err as ApiError).message ?? "Bad code");
    } finally { setLoading(false); }
  }

  async function savePassword(e: FormEvent) {
    e.preventDefault();
    if (newPwd.length < 8) { toast.error("Password must be at least 8 characters"); return; }
    if (newPwd !== newPwd2) { toast.error("Passwords don't match"); return; }
    setLoading(true);
    try {
      await apiPost("/api/v1/me/password", { newPassword: newPwd, viaOtp: true });
      toast.success("Password saved");
      nav("/app/overview");
    } catch (err) {
      toast.error((err as ApiError).message ?? "Could not set password");
    } finally { setLoading(false); }
  }

  function skipPassword() {
    if (step.kind !== "otp_set_password") return;
    finishSignIn(step.auth);
  }

  function finishSignIn(r: AuthResponse) {
    setAuth(r.accessToken, r.refreshToken, {
      id: r.user.id, email: r.user.email, fullName: r.user.fullName,
      role: r.user.role, status: r.user.status, avatarUrl: r.user.avatarUrl,
    });
    toast.success(`Welcome, ${r.user.fullName?.split(" ")[0] ?? ""}`);
    nav("/app/overview");
  }

  // resend countdown
  useEffect(() => {
    if (resendIn <= 0) return;
    const t = setTimeout(() => setResendIn(resendIn - 1), 1000);
    return () => clearTimeout(t);
  }, [resendIn]);

  async function resendCode() {
    if (step.kind !== "otp_verify") return;
    setLoading(true);
    try {
      await apiPost("/auth/email/request", { email });
      toast.success("Code re-sent");
      setResendIn(30);
    } catch (e) {
      toast.error((e as ApiError).message ?? "Could not send code");
    } finally { setLoading(false); }
  }

  /* ── Render ─────────────────────────────────────────────── */

  return (
    <div className="min-h-screen grid lg:grid-cols-2 bg-[#0a0d28] relative overflow-hidden">
      {/* Vanta animated globe — full-page background */}
      <VantaGlobe className="absolute inset-0 z-0"/>
      {/* Dark wash so foreground text/form stays readable */}
      <div className="absolute inset-0 z-[1] bg-gradient-to-br from-[#0a0d28]/40 via-[#0a0d28]/55 to-[#0a0d28]/85"/>
      <div className="absolute inset-0 z-[1] dotted-grid opacity-[0.08]"/>

      {/* Brand panel */}
      <div className="hidden lg:flex relative z-10 text-white">
        <div className="relative z-10 flex flex-col justify-between p-12 w-full">
          <Logo dark />
          <div>
            <motion.h1
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              className="text-5xl lg:text-6xl font-semibold leading-[1.04] tracking-[-0.035em] drop-shadow-[0_2px_24px_rgba(11,9,57,0.6)]"
            >
              Cambodia's modern
              <br/>
              <span className="bg-gradient-to-r from-white via-accent-200 to-white bg-clip-text text-transparent">
                payment gateway.
              </span>
            </motion.h1>
            <p className="mt-5 max-w-md text-white/85 text-[17px] leading-[1.55]">
              ABA · ACLEDA · Wing · Bakong — accept all four through one API.
            </p>
            <div className="mt-10 grid grid-cols-3 gap-3 max-w-md">
              {[
                { l: "Banks",     v: "4",      d: "all major" },
                { l: "Latency",   v: "<200ms", d: "QR mint" },
                { l: "Free now",  v: "100%",   d: "no fees" },
              ].map((s) => (
                <div key={s.l} className="rounded-2xl bg-white/10 backdrop-blur-sm p-4 ring-1 ring-white/15">
                  <div className="text-2xl font-bold tabular-nums">{s.v}</div>
                  <div className="text-[10px] uppercase tracking-wider opacity-75 mt-1">{s.l}</div>
                  <div className="text-[10px] opacity-60 mt-0.5">{s.d}</div>
                </div>
              ))}
            </div>
          </div>
          <p className="text-xs text-white/60">© {new Date().getFullYear()} Byme Bank.</p>
        </div>
      </div>

      {/* Form */}
      <div className="relative z-10 flex items-center justify-center p-6 sm:p-12">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          className="w-full max-w-md rounded-2xl bg-white/[.04] ring-1 ring-white/10 backdrop-blur-xl p-7 sm:p-9 shadow-[0_24px_60px_-20px_rgba(0,0,0,.6)]"
        >
          <div className="lg:hidden mb-6"><Logo dark /></div>

          <h2 className="text-3xl font-semibold text-white tracking-tight">
            {step.kind === "password" ? `Welcome back, ${step.existing.fullName?.split(" ")[0] ?? ""}` : "Sign in"}
          </h2>
          <p className="mt-1 text-white/65 text-sm">
            {step.kind === "password"
              ? <>Enter your password for <strong className="text-white/85">{step.email}</strong>.</>
              : "We'll pick the right method for you."}
          </p>

          <AnimatePresence mode="wait">
            {/* ── Step 1: email entry + Google ─────────── */}
            {step.kind === "email" && (
              <motion.form key="email" onSubmit={continueWithEmail}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
                className="mt-6 space-y-4"
              >
                {GOOGLE_CLIENT_ID && (
                  <>
                    <div ref={googleBtnRef} className="flex justify-center"/>
                    <div className="relative my-2">
                      <div className="absolute inset-0 flex items-center">
                        <div className="w-full border-t border-white/10"/>
                      </div>
                      <div className="relative flex justify-center">
                        <span className="bg-[#0a0d28]/0 px-3 text-[10px] uppercase tracking-widest text-white/40">
                          or
                        </span>
                      </div>
                    </div>
                  </>
                )}

                <div>
                  <label className="block text-sm font-medium text-white/80 mb-1.5">Email</label>
                  <div className="relative">
                    <Mail size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/40"/>
                    <input
                      className="w-full rounded-xl bg-white/[.06] pl-9 pr-3 py-2.5 text-sm text-white ring-1 ring-white/15 placeholder:text-white/35 focus:outline-none focus:ring-2 focus:ring-brand-400/60 focus:bg-white/[.10] transition"
                      type="email" autoFocus required
                      value={email} onChange={(e) => setEmail(e.target.value)}
                      placeholder="you@gmail.com"/>
                  </div>
                  <p className="mt-1.5 text-xs text-white/50">If you have an account we'll ask for your password. Otherwise we'll send a one-time code.</p>
                </div>

                <button className="btn-primary w-full" disabled={loading}>
                  {loading ? <Loader2 size={16} className="animate-spin"/> : <>Continue <ArrowRight size={14}/></>}
                </button>
              </motion.form>
            )}

            {/* ── Password ─────────────────────────────── */}
            {step.kind === "password" && (
              <motion.form key="pw" onSubmit={passwordLogin}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
                className="mt-6 space-y-4"
              >
                <button type="button"
                        onClick={() => { setStep({ kind: "email" }); setPassword(""); }}
                        className="inline-flex items-center gap-1 text-xs font-medium text-white/60 hover:text-white transition">
                  <ArrowLeft size={12}/> Different email
                </button>

                {/* Account chip */}
                <div className="flex items-center gap-3 rounded-xl bg-white/[.06] ring-1 ring-white/10 p-3">
                  {step.existing.avatarUrl
                    ? <img src={step.existing.avatarUrl} alt=""
                           className="h-10 w-10 rounded-full object-cover"/>
                    : <div className="h-10 w-10 rounded-full bg-gradient-to-br from-brand-500 to-brand-800 text-white grid place-items-center font-bold">
                        {step.existing.fullName?.charAt(0) ?? step.email.charAt(0).toUpperCase()}
                      </div>}
                  <div className="min-w-0">
                    <div className="font-semibold text-white truncate">{step.existing.fullName ?? "—"}</div>
                    <div className="text-xs text-white/55 truncate">{step.email}</div>
                  </div>
                </div>

                <div>
                  <div className="flex justify-between items-baseline">
                    <label className="block text-sm font-medium text-white/80 mb-1.5">Password</label>
                    <button type="button"
                            onClick={async () => {
                              setLoading(true);
                              try {
                                await apiPost("/auth/email/request", { email });
                                toast.success("Code sent — check your Gmail");
                                setStep({ kind: "otp_verify", email, isNew: false });
                                setResendIn(30);
                                setCode("");
                              } catch (err) {
                                toast.error((err as ApiError).message ?? "Could not send code");
                              } finally { setLoading(false); }
                            }}
                            className="text-xs font-semibold text-accent-300 hover:text-accent-200 transition">
                      Forgot? Use a code
                    </button>
                  </div>
                  <div className="relative">
                    <Lock size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/40"/>
                    <input
                      className="w-full rounded-xl bg-white/[.06] pl-9 pr-10 py-2.5 text-sm text-white ring-1 ring-white/15 placeholder:text-white/35 focus:outline-none focus:ring-2 focus:ring-brand-400/60 focus:bg-white/[.10] transition"
                      type={showPwd ? "text" : "password"}
                      autoFocus required minLength={8}
                      value={password} onChange={(e) => setPassword(e.target.value)}
                      placeholder="••••••••"/>
                    <button type="button" onClick={() => setShowPwd(!showPwd)}
                            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-white/45 hover:text-white">
                      {showPwd ? <EyeOff size={16}/> : <Eye size={16}/>}
                    </button>
                  </div>
                </div>

                <button className="btn-primary w-full" disabled={loading}>
                  {loading ? <Loader2 size={16} className="animate-spin"/> : "Sign in"}
                </button>
              </motion.form>
            )}

            {/* ── OTP verify ───────────────────────────── */}
            {step.kind === "otp_verify" && (
              <motion.form key="ov" onSubmit={verifyCode}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
                className="mt-6 space-y-4"
              >
                <button type="button"
                        onClick={() => { setStep({ kind: "email" }); setCode(""); }}
                        className="inline-flex items-center gap-1 text-xs font-medium text-white/60 hover:text-white transition">
                  <ArrowLeft size={12}/> Different email
                </button>

                <div className="rounded-xl bg-brand-500/10 ring-1 ring-brand-400/30 p-3 text-xs text-brand-100">
                  Code sent to <strong className="text-white">{step.email}</strong>. Check spam if you don't see it.
                </div>

                <div>
                  <label className="block text-sm font-medium text-white/80 mb-1.5">6-digit code</label>
                  <div className="relative">
                    <KeyRound size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/40"/>
                    <input
                      className="w-full rounded-xl bg-white/[.06] pl-9 pr-3 py-3 text-white ring-1 ring-white/15 placeholder:text-white/30 focus:outline-none focus:ring-2 focus:ring-brand-400/60 focus:bg-white/[.10] transition font-mono tracking-[0.4em] text-center text-lg"
                      type="text" inputMode="numeric" pattern="\d{6}" maxLength={6}
                      autoFocus required
                      value={code}
                      onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                      placeholder="• • • • • •"/>
                  </div>
                </div>

                <button className="btn-primary w-full" disabled={loading || code.length !== 6}>
                  {loading
                    ? <Loader2 size={16} className="animate-spin"/>
                    : <><ShieldCheck size={14}/> Verify & sign in</>}
                </button>

                <div className="text-center text-xs text-white/55">
                  Didn't get it?{" "}
                  {resendIn > 0
                    ? <span>Resend in {resendIn}s</span>
                    : <button type="button" onClick={resendCode}
                              className="font-semibold text-accent-300 hover:text-accent-200 transition">
                        Resend
                      </button>}
                </div>
              </motion.form>
            )}

            {/* ── Set password (only NEW users) ──────── */}
            {step.kind === "otp_set_password" && (
              <motion.form key="sp" onSubmit={savePassword}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
                className="mt-6 space-y-4"
              >
                <div className="rounded-xl bg-accent-500/15 ring-1 ring-accent-400/30 p-3 text-xs text-accent-100 flex items-center gap-2">
                  <Check size={14}/> Welcome! Signed in as <strong className="text-white">{step.auth.user.email}</strong>
                </div>

                <h3 className="text-lg font-semibold text-white">Set a password (optional)</h3>
                <p className="text-sm text-white/60 -mt-1">
                  This lets you sign in with email + password next time. Skip to keep using email codes.
                </p>

                <div>
                  <label className="block text-sm font-medium text-white/80 mb-1.5">New password</label>
                  <div className="relative">
                    <Lock size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/40"/>
                    <input
                      className="w-full rounded-xl bg-white/[.06] pl-9 pr-10 py-2.5 text-sm text-white ring-1 ring-white/15 placeholder:text-white/35 focus:outline-none focus:ring-2 focus:ring-brand-400/60 focus:bg-white/[.10] transition"
                      type={showPwd ? "text" : "password"}
                      autoFocus minLength={8} maxLength={100}
                      value={newPwd}
                      onChange={(e) => setNewPwd(e.target.value)}
                      placeholder="At least 8 characters"/>
                    <button type="button" onClick={() => setShowPwd(!showPwd)}
                            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-white/45 hover:text-white">
                      {showPwd ? <EyeOff size={16}/> : <Eye size={16}/>}
                    </button>
                  </div>
                </div>
                <div>
                  <label className="block text-sm font-medium text-white/80 mb-1.5">Confirm password</label>
                  <div className="relative">
                    <Lock size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/40"/>
                    <input
                      className="w-full rounded-xl bg-white/[.06] pl-9 pr-3 py-2.5 text-sm text-white ring-1 ring-white/15 placeholder:text-white/35 focus:outline-none focus:ring-2 focus:ring-brand-400/60 focus:bg-white/[.10] transition"
                      type={showPwd ? "text" : "password"}
                      minLength={8} maxLength={100}
                      value={newPwd2}
                      onChange={(e) => setNewPwd2(e.target.value)}
                      placeholder="Repeat password"/>
                  </div>
                </div>

                <div className="flex gap-2">
                  <button type="button" onClick={skipPassword}
                          className="flex-1 inline-flex items-center justify-center rounded-xl bg-white/10 ring-1 ring-white/15 text-white px-4 py-2.5 text-sm font-semibold hover:bg-white/15 transition">
                    Skip for now
                  </button>
                  <button className="btn-primary flex-1" disabled={loading}>
                    {loading
                      ? <Loader2 size={16} className="animate-spin"/>
                      : "Save & continue"}
                  </button>
                </div>
              </motion.form>
            )}
          </AnimatePresence>

          <p className="mt-7 text-sm text-white/60 text-center">
            <Link to="/" className="font-semibold text-accent-300 hover:text-accent-200 transition">
              ← Back to home
            </Link>
          </p>
        </motion.div>
      </div>
    </div>
  );
}
