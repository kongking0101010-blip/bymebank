import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import {
  FlaskConical, Loader2, ScanQrCode, Check, X, RefreshCw,
  Copy, Eye, EyeOff, KeyRound, Clock, ChevronDown, ChevronRight,
  Plus, AlertCircle,
} from "lucide-react";
import toast from "react-hot-toast";

import { apiGet, apiPost, ApiError } from "@/api/client";
import { BankBadge, BankType } from "@/components/BankBadge";
import { TelegramNotifyPanel } from "@/components/TelegramNotifyPanel";
import {
  useLinkedBanks,
  summariseLinkedBanks,
  BANK_LABELS,
} from "@/hooks/useLinkedBanks";

const POLL_INTERVAL_MS = 3_000;
const POLL_TIMEOUT_MS  = 5 * 60 * 1_000; // 5 minutes

interface UpstreamKey {
  hasKey: boolean; key?: string; expiresAt?: string;
  banks?: string[];
}

interface KeyRow {
  id: string;
  key: string;
  label?: string | null;
  merchantName?: string;
  planId?: string;
  issuedAt?: string;
  expiresAt?: string;
  revoked: boolean;
  primary: boolean;
  expired: boolean;
  daysRemaining: number;
  banks: string[];
}
interface TestQrResp {
  md5: string;
  qrImage: string;
  qrString: string;
  merchantName?: string;
  bank: string;
  amount: number;
  currency: string;
}
interface PaymentStatus {
  md5: string;
  status: "PAID" | "UNPAID" | "ERROR" | string;
  paid: boolean;
  amount?: number;
  currency?: string;
  from?: string;
  timestamp?: string;
  /** Real Bakong tx hash returned by /api/check_payment when PAID. */
  hash?: string;
  /** Bank tran_id (e.g. "100FT37845835941"). */
  externalRef?: string;
  /** Remark / note attached to the payment. */
  description?: string;
  /** Receiver account id ("To (You)" line in the DM). */
  to?: string;
  /** Exact tx time in epoch millis. */
  createdAtMs?: number;
}

type PollPhase =
  | { kind: "idle" }
  | { kind: "polling"; elapsed: number }
  | { kind: "paid"; status: PaymentStatus }
  | { kind: "stopped" };

export default function TestKey() {
  // Single-key view (used for the masked sk_ display)
  const upstreamQ = useQuery<UpstreamKey>({
    queryKey: ["upstream-key", "reveal"],
    queryFn: () => apiGet("/api/v1/me/upstream-key?reveal=true"),
    refetchOnWindowFocus: true,
    refetchOnMount: "always",
    refetchInterval: 15_000,
    staleTime: 0,
  });

  // All-keys list — drives the picker so users can test any of their keys.
  const keysQ = useQuery<KeyRow[]>({
    queryKey: ["upstream-key", "list", "reveal"],
    queryFn: () => apiGet("/api/v1/me/upstream-key/list?reveal=true"),
    refetchOnWindowFocus: true,
    refetchOnMount: "always",
    refetchInterval: 15_000,
    staleTime: 0,
  });

  // Only keys that are actually usable for testing.
  const usableKeys: KeyRow[] = (keysQ.data ?? [])
      .filter((k) => !k.revoked && !k.expired);

  const [selectedKeyId, setSelectedKeyId] = useState<string | null>(null);

  // Auto-pick the primary key (or the first usable one) when the list arrives.
  useEffect(() => {
    if (usableKeys.length === 0) {
      if (selectedKeyId !== null) setSelectedKeyId(null);
      return;
    }
    if (!selectedKeyId || !usableKeys.some((k) => k.id === selectedKeyId)) {
      const primary = usableKeys.find((k) => k.primary);
      setSelectedKeyId((primary ?? usableKeys[0]).id);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usableKeys.map((k) => k.id).join(",")]);

  const selectedKey = usableKeys.find((k) => k.id === selectedKeyId) ?? null;

  const [showKey, setShowKey] = useState(false);
  // Bank starts unset — we lock it in once the live linked-banks list arrives.
  // No more hardcoded "BAKONG" default that lies about what's actually linked.
  const [bank, setBank] = useState<BankType | null>(null);
  const [amount, setAmount] = useState("1.50");
  const [currency, setCurrency] = useState<"USD" | "KHR">("USD");
  const [showQrString, setShowQrString] = useState(false);

  const [qr, setQr]   = useState<TestQrResp | null>(null);
  const [poll, setPoll] = useState<PollPhase>({ kind: "idle" });
  const pollTimer  = useRef<number | null>(null);
  const tickTimer  = useRef<number | null>(null);
  const pollStartRef = useRef<number>(0);

  const skKey = selectedKey?.key ?? upstreamQ.data?.key ?? "";
  const hasSk = !!skKey && /^sk_[a-f0-9]{32}$/.test(skKey);

  // Authoritative bank list — pulled live from upstream /key_info via Spring.
  // No client-side fallback to "all four" exists ON PURPOSE: showing a bank
  // the user hasn't actually linked is the bug we're fixing here.
  const linked = useLinkedBanks(selectedKey?.id ?? null);
  const banks: BankType[] = linked.banks;

  /* ── generate ─────────────────────────────────────── */
  const generate = useMutation({
    mutationFn: async (): Promise<TestQrResp> => {
      const amt = parseFloat(amount);
      if (!(amt > 0)) throw new Error("Amount must be > 0");
      if (!bank) throw new Error("Pick a linked bank first");
      return apiPost<TestQrResp>(
          "/api/v1/me/upstream-key/payment-qr/test",
          {
            bank: bank.toLowerCase(),
            amount: amt,
            currency,
            // Tell the backend exactly which key to charge with.
            keyId: selectedKey?.id,
          });
    },
    onSuccess: (r) => {
      stopPolling();
      setQr(r);
      startPolling(r.md5);
    },
    onError: (e: ApiError | Error) => {
      const apiErr = e as ApiError;
      if (apiErr?.code === "NO_ACTIVE_KEY") {
        toast.error("Your sk_ key was removed. Mint a new one in Buy API Key.");
      } else {
        toast.error(apiErr.message ?? e.message ?? "Generate failed");
      }
    },
  });

  function startPolling(md5: string) {
    setPoll({ kind: "polling", elapsed: 0 });
    pollStartRef.current = Date.now();
    // Per-run guard so a paid response from the immediate tick can't be
    // overwritten by a follow-up interval tick.
    let stopped = false;

    const checkPaid = async () => {
      if (stopped) return;
      try {
        const s = await apiGet<PaymentStatus>(
            `/api/v1/me/upstream-key/payment-status/${encodeURIComponent(md5)}`);
        if (stopped) return;
        if (s.paid || s.status === "PAID") {
          stopped = true;
          stopPolling();
          setPoll({ kind: "paid", status: s });
          // Sidecar Telegram DM trigger. Server-side polling already
          // fires this once when the txn first flips to PAID, but we
          // also call it here so the DM still lands if the user is
          // hitting a path that doesn't go through the controller's
          // status-flip block. Idempotent upstream — dedup by (key,
          // md5), so the worst case is "Already notified...".
          if (skKey && /^sk_[a-f0-9]{32}$/.test(skKey)) {
            apiPost("/api/v1/notify/paid", {
              key:           skKey,
              md5:           s.md5,
              amount:        s.amount,
              currency:      s.currency ?? "USD",
              from:          s.from ?? "",
              bank:          s.from ? "" : (bank ? bank.toLowerCase() : ""),
              timestamp:     s.timestamp,
              // Real Bakong context — forwarded so the bot's "Short Hash"
              // line in the DM matches what api-bakong.nbc.gov.kh accepts
              // instead of falling back to md5[:8].
              hash:          s.hash,
              external_ref:  s.externalRef,
              description:   s.description,
              to:            s.to,
              created_at_ms: s.createdAtMs,
            }).catch(() => { /* swallow — best-effort */ });
          }
        }
      } catch {
        /* swallow transient errors so polling continues */
      }
    };

    // Display ticker — updates the elapsed-seconds counter every 1 s
    // independent of the network poll, so the timer never jumps in 3-second
    // chunks and never sits at 0.
    const displayTick = () => {
      if (stopped) return;
      const elapsed = Date.now() - pollStartRef.current;
      if (elapsed >= POLL_TIMEOUT_MS) {
        stopped = true;
        stopPolling();
        setPoll({ kind: "stopped" });
        return;
      }
      setPoll((p) => (p.kind === "polling" ? { kind: "polling", elapsed } : p));
    };

    // Network poll — every 3 s.
    pollTimer.current = window.setInterval(checkPaid, POLL_INTERVAL_MS);
    // Smooth display counter — every 1 s.
    tickTimer.current = window.setInterval(displayTick, 1_000);
    // Fire the first network check right away.
    checkPaid();
  }

  function stopPolling() {
    if (pollTimer.current) window.clearInterval(pollTimer.current);
    if (tickTimer.current) window.clearInterval(tickTimer.current);
    pollTimer.current = null;
    tickTimer.current = null;
  }

  function reset() {
    stopPolling();
    setQr(null);
    setPoll({ kind: "idle" });
  }

  // Restart polling when bank/amount/currency change AFTER a QR exists
  useEffect(() => () => stopPolling(), []);

  // When the user picks a different key, drop any current QR + polling so
  // we don't accidentally check the wrong key's payment.
  useEffect(() => { reset(); /* eslint-disable-next-line */ }, [selectedKeyId]);

  // The MOMENT the active sk_ key disappears (user removed it from the Keys
  // page, was revoked by the bridge, etc), kill the live QR + polling so
  // nothing stale stays on screen.
  useEffect(() => {
    if (!upstreamQ.isLoading && !hasSk) {
      stopPolling();
      setQr(null);
      setPoll({ kind: "idle" });
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasSk, upstreamQ.isLoading]);

  // Lock the selected bank to one that's actually linked to this key. Runs
  // after every linked-banks refresh — if the user removes the bank they
  // had selected, snap to the next one. Never auto-pick when the list is
  // empty (we render an "no banks linked" banner in that case instead).
  useEffect(() => {
    if (banks.length === 0) {
      if (bank !== null) {
        setBank(null);
        reset();
      }
      return;
    }
    if (bank === null || !banks.includes(bank)) {
      setBank(banks[0]);
      reset();
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [banks.join(",")]);

  // Single source of truth for the success notification — only fires when
  // we transition INTO the "paid" phase. Avoids double-toasts even if
  // setPoll is called more than once.
  const lastToastedMd5 = useRef<string | null>(null);
  useEffect(() => {
    if (poll.kind === "paid" && lastToastedMd5.current !== poll.status.md5) {
      lastToastedMd5.current = poll.status.md5;
      toast.success("Payment received");
    }
  }, [poll]);

  /* ── render ───────────────────────────────────────── */
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight flex items-center gap-2">
          <FlaskConical size={20} className="text-brand-600"/> Test API Key
        </h1>
        <p className="text-ink-500 mt-1">
          Sandbox playground for your saved <code className="font-mono text-xs bg-ink-100 rounded px-1.5 py-0.5">sk_</code> key.
          Generate a QR, scan with any KHQR app, watch the status flip when the customer pays.
        </p>
      </div>

      {/* Saved key card + picker */}
      <div className="relative overflow-hidden rounded-2xl text-white p-5 ring-1 ring-white/10 shadow-[0_24px_60px_-24px_rgba(15,23,42,.45)]"
           style={{
             background:
               "radial-gradient(80% 100% at 0% 0%, rgba(58,85,255,.30) 0%, transparent 60%)," +
               "linear-gradient(180deg, #1a1f2e 0%, #0a0d14 100%)",
           }}>
        <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-white/20"/>
        <div className="flex items-center gap-2 mb-3">
          <KeyRound size={16} className="text-accent-300"/>
          <span className="font-bold text-sm">Test with</span>
          {selectedKey && (
            <span className="text-[9px] font-extrabold tracking-widest uppercase rounded-full bg-accent-400/20 text-accent-200 ring-1 ring-accent-400/30 px-2 py-0.5">
              {selectedKey.label || "SK"}
            </span>
          )}
          <span className="ml-auto text-[10px] font-mono text-white/45 tabular-nums">
            {usableKeys.length} key{usableKeys.length === 1 ? "" : "s"}
          </span>
        </div>

        {upstreamQ.isLoading || keysQ.isLoading ? (
          <div className="text-white/70 text-sm">
            <Loader2 className="inline animate-spin" size={14}/> Loading…
          </div>
        ) : usableKeys.length === 0 ? (
          <div className="text-sm text-white/85">
            No active sk_ key — head to{" "}
            <Link to="/app/buy" className="font-bold underline text-accent-300 hover:text-accent-200">Buy API Key</Link>{" "}
            to mint one.
          </div>
        ) : (
          <div className="space-y-3">
            {/* Picker (only shown when there are 2+ keys) */}
            {usableKeys.length > 1 && (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {usableKeys.map((k) => {
                  const sel = k.id === selectedKeyId;
                  return (
                    <button
                      type="button"
                      key={k.id}
                      onClick={() => setSelectedKeyId(k.id)}
                      className={`text-left rounded-xl px-3 py-2.5 ring-1 transition ${
                        sel
                          ? "bg-white/12 ring-white/30"
                          : "bg-white/5 ring-white/10 hover:bg-white/10 hover:ring-white/20"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-sm truncate">{k.label || "Untitled"}</span>
                        {k.primary && (
                          <span className="text-[9px] font-extrabold tracking-widest uppercase rounded-full bg-accent-400/20 text-accent-200 ring-1 ring-accent-400/30 px-1.5 py-0.5">
                            primary
                          </span>
                        )}
                      </div>
                      <div className="mt-0.5 text-[11px] font-mono text-white/55 truncate">
                        {sel
                          ? (showKey ? k.key : maskKey(k.key))
                          : maskKey(k.key)}
                      </div>
                      <div className="mt-1 text-[10px] text-white/40">
                        {(k.banks || []).map((b) => b.toUpperCase()).join(" · ") || "—"}
                      </div>
                    </button>
                  );
                })}
              </div>
            )}

            {/* Selected key, full row */}
            {selectedKey && (
              <div className="flex items-center gap-2 rounded-xl bg-black/25 ring-1 ring-white/5 px-3 py-2">
                <code className="flex-1 font-mono text-sm break-all text-white/95">
                  {showKey ? selectedKey.key : maskKey(selectedKey.key)}
                </code>
                <button onClick={() => setShowKey(!showKey)}
                        className="text-white/55 hover:text-white p-1.5 rounded-md hover:bg-white/5 transition"
                        title={showKey ? "Hide key" : "Reveal key"}>
                  {showKey ? <EyeOff size={14}/> : <Eye size={14}/>}
                </button>
                <button onClick={() => { navigator.clipboard.writeText(selectedKey.key); toast.success("Copied"); }}
                        className="text-white/55 hover:text-white p-1.5 rounded-md hover:bg-white/5 transition"
                        title="Copy key">
                  <Copy size={14}/>
                </button>
              </div>
            )}

            {/* Live linked-banks summary — exact, no padding. */}
            {selectedKey && !linked.loading && linked.valid && banks.length > 0 && (
              <div className="text-[11px] text-white/55">
                {summariseLinkedBanks(banks)}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Form + result grid */}
      <div className="grid lg:grid-cols-5 gap-6">
        {/* Form */}
        <div className="lg:col-span-2 space-y-4">
          {/* Telegram notifications — lives above the form so users see it
              the moment they pick a key. Compact variant to keep the form
              dominant; expands inline when not yet linked. */}
          {hasSk && (
            <TelegramNotifyPanel apiKey={skKey} variant="compact"/>
          )}

          <div className="card card-pad space-y-4">
          <div>
            <label className="label">Bank</label>
            <BankPicker
              loading={linked.loading}
              valid={linked.valid}
              hasKey={hasSk}
              banks={banks}
              reason={linked.reason}
              selected={bank}
              onSelect={(b) => { setBank(b); reset(); }}
            />
          </div>

          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-2">
              <label className="label">Amount</label>
              <input className="input" type="number" min="0.01" step="0.01"
                     value={amount}
                     onChange={(e) => { setAmount(e.target.value); reset(); }}/>
            </div>
            <div>
              <label className="label">Currency</label>
              <select className="input"
                      value={currency}
                      onChange={(e) => { setCurrency(e.target.value as "USD" | "KHR"); reset(); }}>
                <option value="USD">USD</option>
                <option value="KHR">KHR</option>
              </select>
            </div>
          </div>

          <button onClick={() => generate.mutate()}
                  disabled={!hasSk || !bank || generate.isPending}
                  className="btn-primary w-full">
            {generate.isPending
              ? <Loader2 size={14} className="animate-spin"/>
              : <><ScanQrCode size={14}/> Generate Test QR</>}
          </button>

          {!hasSk && !upstreamQ.isLoading && (
            <div className="rounded-xl bg-amber-50 ring-1 ring-amber-200 p-3.5 text-xs text-amber-900 space-y-2">
              <div className="flex items-start gap-2">
                <AlertCircle size={14} className="text-amber-600 mt-0.5 shrink-0"/>
                <div>
                  <div className="font-bold text-sm">No active sk_ key</div>
                  <p className="mt-0.5 text-amber-800/85 leading-snug">
                    Your previous key was removed. Mint a new one to keep using the
                    Test page and your live integration.
                  </p>
                </div>
              </div>
              <Link to="/app/buy"
                    className="btn-primary w-full !py-2 !text-xs justify-center">
                <Plus size={13}/> Buy a new API key
              </Link>
            </div>
          )}
          </div>
        </div>

        {/* Result: KHQR card + status */}
        <div className="lg:col-span-3">
          {!qr ? (
            <div className="card card-pad grid place-items-center text-center text-ink-400 py-20">
              <div>
                <div className="mx-auto h-16 w-16 rounded-full bg-ink-100 grid place-items-center">
                  <ScanQrCode size={28} className="opacity-50"/>
                </div>
                <p className="mt-3 text-sm">Generate a QR to preview it here.</p>
              </div>
            </div>
          ) : poll.kind === "paid" ? (
            // ── ONLY show the celebration when paid ──
            <PaidCard
              qr={qr}
              status={poll.status}
              onNew={() => generate.mutate()}
              onReset={reset}
              busy={generate.isPending}
            />
          ) : (
            <div className="grid md:grid-cols-[300px_1fr] gap-5 items-start">
              <div>
                <KhqrCard qr={qr}/>
                <p className="mt-2 text-center text-xs text-ink-500">
                  Scan with any KHQR app
                </p>
              </div>
              <div className="space-y-3">
                <StatusPanel poll={poll}/>

                <div className="rounded-2xl bg-white ring-1 ring-ink-100 p-4 text-xs space-y-2">
                  <Row label="MD5"  value={qr.md5} mono/>
                  <Row label="Bank" value={qr.bank.toUpperCase()}/>
                  <Row label="Amount"
                       value={`${qr.amount} ${qr.currency}`}/>
                  {qr.merchantName && <Row label="Merchant" value={qr.merchantName}/>}
                </div>

                <button onClick={() => setShowQrString((v) => !v)}
                        className="text-xs font-semibold text-ink-600 hover:text-ink-900 inline-flex items-center gap-1">
                  {showQrString ? <ChevronDown size={12}/> : <ChevronRight size={12}/>}
                  Technical details
                </button>
                {showQrString && (
                  <code className="block rounded bg-ink-900 text-emerald-300 p-3 text-[10px] font-mono break-all max-h-40 overflow-y-auto">
                    {qr.qrString}
                  </code>
                )}

                <div className="flex gap-2 pt-1">
                  <button onClick={() => generate.mutate()}
                          className="btn-secondary flex-1"
                          disabled={generate.isPending}>
                    <RefreshCw size={14}/> New QR
                  </button>
                  <button onClick={reset} className="btn-ghost">
                    <X size={14}/> Reset
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/* ───────────── Paid celebration card ────────────────────── */

function PaidCard({
  qr, status, onNew, onReset, busy,
}: {
  qr: TestQrResp;
  status: PaymentStatus;
  onNew: () => void;
  onReset: () => void;
  busy: boolean;
}) {
  const amount   = status.amount ?? qr.amount;
  const currency = status.currency ?? qr.currency;
  const decimals = currency === "KHR" ? 0 : 2;
  const formatted = Number(amount).toLocaleString("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });

  const when = status.timestamp
    ? new Date(status.timestamp).toLocaleString("en-GB", {
        day: "2-digit", month: "short", year: "numeric",
        hour: "2-digit", minute: "2-digit", hour12: false,
      })
    : new Date().toLocaleString("en-GB", {
        day: "2-digit", month: "short", year: "numeric",
        hour: "2-digit", minute: "2-digit", hour12: false,
      });

  return (
    <motion.div
      key="paid-card"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.21, 0.61, 0.35, 1] }}
      className="relative overflow-hidden rounded-2xl bg-white ring-1 ring-ink-100 shadow-[0_24px_60px_-24px_rgba(15,23,42,.18)]"
    >
      {/* subtle emerald success bar at the top */}
      <div className="h-1 bg-gradient-to-r from-emerald-400 via-emerald-500 to-emerald-400"/>

      <div className="px-7 sm:px-10 pt-9 pb-7">
        {/* Tick badge */}
        <motion.div
          initial={{ scale: 0.4, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={{ type: "spring", stiffness: 380, damping: 22, delay: 0.05 }}
          className="mx-auto h-14 w-14 rounded-full bg-emerald-50 ring-1 ring-emerald-200 grid place-items-center"
        >
          <svg viewBox="0 0 24 24" className="h-7 w-7 text-emerald-600" fill="none"
               stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
            <motion.path
              d="M4.5 12.5l4.5 4.5L19.5 6.5"
              initial={{ pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.45, ease: "easeOut", delay: 0.18 }}
            />
          </svg>
        </motion.div>

        {/* Eyebrow + title */}
        <div className="mt-5 text-center">
          <div className="text-[10px] uppercase tracking-[0.22em] font-extrabold text-emerald-600">
            Payment received
          </div>
          <div className="mt-2 flex items-baseline justify-center gap-1.5">
            <span className="text-[44px] sm:text-[52px] font-semibold tracking-[-0.03em] text-ink-900 tabular-nums leading-none">
              {formatted}
            </span>
            <span className="text-base font-semibold text-ink-500">{currency}</span>
          </div>
          {status.from && (
            <div className="mt-2 text-sm text-ink-600">
              from <span className="font-semibold text-ink-900">{status.from}</span>
            </div>
          )}
        </div>

        {/* Receipt rows — quiet, not glowy */}
        <dl className="mt-7 divide-y divide-ink-100 text-[13px]">
          {qr.merchantName && (
            <Receipt label="Merchant" value={qr.merchantName}/>
          )}
          <Receipt label="Bank" value={qr.bank.toUpperCase()}/>
          <Receipt label="Date"  value={when}/>
          <Receipt label="Reference" value={qr.md5} mono truncate/>
        </dl>
      </div>

      {/* Footer actions */}
      <div className="px-7 sm:px-10 py-4 bg-ink-50/70 border-t border-ink-100 flex flex-wrap items-center justify-between gap-3">
        <div className="text-[11px] text-ink-500 font-mono">
          test mode · sandbox
        </div>
        <div className="flex gap-2">
          <button
            onClick={onReset}
            className="inline-flex items-center gap-1.5 rounded-full text-sm font-medium text-ink-600 hover:text-ink-900 px-3 py-2 transition"
          >
            Done
          </button>
          <button
            onClick={onNew}
            disabled={busy}
            className="inline-flex items-center gap-1.5 rounded-full bg-ink-900 text-white px-4 py-2 text-sm font-semibold hover:bg-ink-950 transition disabled:opacity-60"
          >
            <RefreshCw size={13} className={busy ? "animate-spin" : ""}/>
            {busy ? "Generating…" : "Test another payment"}
          </button>
        </div>
      </div>
    </motion.div>
  );
}

function Receipt({ label, value, mono, truncate }: {
  label: string; value: string; mono?: boolean; truncate?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5">
      <dt className="text-ink-500">{label}</dt>
      <dd className={`font-medium text-ink-900 ${mono ? "font-mono text-xs" : ""} ${truncate ? "max-w-[60%] truncate" : ""}`}>
        {value}
      </dd>
    </div>
  );
}

/* ───────────── KHQR card ────────────────────────────────── */

function KhqrCard({ qr }: { qr: TestQrResp }) {
  const merchant = (qr.merchantName ?? "Merchant").toUpperCase();
  return (
    <div className="relative w-[300px] mx-auto rounded-2xl bg-white ring-1 ring-ink-200 shadow-soft overflow-hidden">
      {/* Red header */}
      <div className="h-11 grid place-items-center"
           style={{ background: "rgb(226,26,26)" }}>
        <span className="text-white font-bold tracking-[0.3em] text-[15px]">KHQR</span>
      </div>
      {/* Red triangle ribbon */}
      <span className="absolute right-0 top-11 block h-0 w-0"
            style={{
              borderLeft:  "18px solid transparent",
              borderTop:   "18px solid rgb(226,26,26)",
            }}/>
      {/* Merchant + amount */}
      <div className="px-5 pt-3 pb-4">
        <div className="text-[11px] uppercase tracking-wider text-slate-700 font-semibold truncate">
          {merchant}
        </div>
        <div className="flex items-baseline gap-1.5 mt-0.5">
          <span className="text-[17px] font-bold text-slate-900">
            {Number(qr.amount).toFixed(qr.currency === "KHR" ? 0 : 2)}
          </span>
          <span className="text-[11px] text-slate-700 font-semibold">{qr.currency}</span>
        </div>
      </div>
      {/* Dashed divider */}
      <div aria-hidden className="h-px mx-5"
           style={{
             backgroundImage:
               "repeating-linear-gradient(90deg, rgba(15,23,42,0.45) 0 6px, transparent 6px 14px)",
             height: 1.2,
           }}/>
      {/* QR */}
      <div className="grid place-items-center py-5 px-5">
        <div className="qr-scan-frame bg-white p-2 rounded-lg ring-1 ring-ink-100 relative">
          <span className="qr-scan-glow"/>
          <span className="qr-scan-corner tl"/>
          <span className="qr-scan-corner tr"/>
          <span className="qr-scan-corner bl"/>
          <span className="qr-scan-corner br"/>
          <span className="qr-scan-beam"/>
          <img src={qr.qrImage} alt="KHQR"
               className="w-[200px] h-[200px] object-contain relative z-[1]"/>
        </div>
      </div>
    </div>
  );
}

/* ───────────── Status panel ─────────────────────────────── */

function StatusPanel({ poll }: { poll: PollPhase }) {
  if (poll.kind === "paid") {
    return (
      <motion.div
        key="paid"
        initial={{ opacity: 0, scale: 0.96, y: 8 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        transition={{ type: "spring", stiffness: 320, damping: 22 }}
        className="relative overflow-hidden rounded-2xl text-white shadow-[0_18px_50px_-12px_rgba(14,207,129,.55)]"
        style={{
          background:
            "radial-gradient(120% 100% at 0% 0%, rgba(255,255,255,.18) 0%, transparent 60%)," +
            "linear-gradient(135deg, #04a868 0%, #0a563b 100%)",
        }}
      >
        <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-white/40"/>
        <div className="px-5 py-4 flex items-start gap-3">
          <motion.div
            initial={{ scale: 0, rotate: -45 }}
            animate={{ scale: 1, rotate: 0 }}
            transition={{ type: "spring", stiffness: 380, damping: 18, delay: 0.05 }}
            className="shrink-0 grid place-items-center h-10 w-10 rounded-full bg-white text-emerald-600 ring-2 ring-white/40"
          >
            <Check size={20} strokeWidth={3}/>
          </motion.div>
          <div className="min-w-0 flex-1">
            <div className="text-[10px] font-extrabold uppercase tracking-[0.22em] text-white/70">
              Payment status
            </div>
            <div className="mt-0.5 text-lg font-bold leading-tight">
              Payment received
            </div>
            <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-sm">
              {poll.status.amount != null && (
                <span className="font-mono font-bold text-white tabular-nums">
                  {poll.status.amount} {poll.status.currency ?? ""}
                </span>
              )}
              {poll.status.from && (
                <span className="text-white/80">
                  from <strong className="text-white">{poll.status.from}</strong>
                </span>
              )}
              {poll.status.timestamp && (
                <span className="text-[11px] font-mono text-white/65">
                  {poll.status.timestamp}
                </span>
              )}
            </div>
          </div>
        </div>
        {/* sub-strip with md5 for proof */}
        <div className="px-5 py-2 border-t border-white/15 bg-black/15 text-[10px] font-mono text-white/65 truncate">
          md5: {poll.status.md5}
        </div>
      </motion.div>
    );
  }
  if (poll.kind === "stopped") {
    return (
      <div className="rounded-2xl ring-1 ring-ink-200 bg-ink-50 px-4 py-3.5 text-sm font-semibold text-ink-700 flex items-center gap-2">
        <Clock size={16}/> Stopped polling — no payment in 5 minutes.
      </div>
    );
  }
  if (poll.kind === "polling") {
    const sec = Math.floor(poll.elapsed / 1000);
    return (
      <div className="rounded-2xl ring-1 ring-brand-300 bg-brand-50 px-4 py-3.5 text-sm font-semibold text-brand-800 flex items-center gap-2">
        <Loader2 size={14} className="animate-spin"/>
        Waiting for payment · {sec}s
      </div>
    );
  }
  return (
    <div className="rounded-2xl ring-1 ring-ink-200 bg-white px-4 py-3.5 text-sm text-ink-500 flex items-center gap-2">
      <Clock size={14}/> Idle
    </div>
  );
}

/* ───────────── helpers ──────────────────────────────────── */

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wider text-ink-500 font-bold">{label}</div>
      <code className={`mt-0.5 block bg-ink-100 rounded px-2 py-1.5 break-all
                        ${mono ? "font-mono" : ""}`}>{value}</code>
    </div>
  );
}

function maskKey(k: string) {
  if (!k) return "—";
  if (k.length <= 12) return k.substring(0, 4) + "…";
  return k.substring(0, 6) + "…" + k.substring(k.length - 4);
}

/* ───────────── BankPicker ───────────────────────────────── */

/**
 * Renders ONE button per linked bank — never more, never less. The whole
 * "show four greyed-out banks" anti-pattern lives here so it can never
 * leak back into the rest of the page.
 *
 * Behaviour:
 *   - loading              → skeleton tiles
 *   - !hasKey              → nothing (the parent already shows a "no key" CTA)
 *   - !valid               → red "invalid key" banner
 *   - valid && banks=[]    → amber "no banks linked yet" banner
 *   - valid && banks=[…]   → live tiles, dynamic grid (1-col when single)
 */
function BankPicker({
  loading, valid, hasKey, banks, reason, selected, onSelect,
}: {
  loading: boolean;
  valid: boolean;
  hasKey: boolean;
  banks: BankType[];
  reason?: string;
  selected: BankType | null;
  onSelect: (b: BankType) => void;
}) {
  if (!hasKey) {
    return (
      <div className="rounded-xl bg-ink-50 ring-1 ring-ink-200 px-3 py-4 text-xs text-ink-500">
        Pick or mint a key to see its linked banks.
      </div>
    );
  }
  if (loading) {
    return (
      <div className="grid grid-cols-2 gap-2" aria-busy="true">
        {[0, 1].map((i) => (
          <div key={i}
               className="rounded-xl border-2 border-ink-200 bg-ink-50 px-3 py-3 h-[68px] animate-pulse"/>
        ))}
      </div>
    );
  }
  if (!valid) {
    return (
      <div className="rounded-xl bg-rose-50 ring-1 ring-rose-200 px-3.5 py-3 text-xs text-rose-900">
        <div className="flex items-start gap-2">
          <AlertCircle size={14} className="text-rose-600 mt-0.5 shrink-0"/>
          <div>
            <div className="font-bold text-sm">Invalid key</div>
            <p className="mt-0.5 text-rose-800/85 leading-snug">
              The bot doesn't recognise this key
              {reason ? <> ({reason})</> : null}. Mint a fresh one in{" "}
              <Link to="/app/buy" className="underline font-semibold">Buy API Key</Link>.
            </p>
          </div>
        </div>
      </div>
    );
  }
  if (banks.length === 0) {
    return (
      <div className="rounded-xl bg-amber-50 ring-1 ring-amber-200 px-3.5 py-3 text-xs text-amber-900">
        <div className="flex items-start gap-2">
          <AlertCircle size={14} className="text-amber-600 mt-0.5 shrink-0"/>
          <div>
            <div className="font-bold text-sm">No banks linked yet</div>
            <p className="mt-0.5 text-amber-800/85 leading-snug">
              Link at least one bank QR before you can generate a test
              payment with this key.
            </p>
          </div>
        </div>
      </div>
    );
  }
  // Single-bank keys deserve the full row — looks intentional, not lonely.
  const cols = banks.length === 1 ? "grid-cols-1" : "grid-cols-2";
  return (
    <div role="radiogroup" aria-label="Linked banks"
         className={`grid ${cols} gap-2`}>
      {banks.map((b) => {
        const sel = selected === b;
        return (
          <button type="button" key={b}
                  role="radio"
                  aria-checked={sel}
                  onClick={() => onSelect(b)}
                  className={`rounded-xl border-2 px-3 py-3 text-left transition ${
                    sel
                      ? "border-brand-500 bg-brand-50 ring-2 ring-brand-500/20"
                      : "border-ink-200 hover:border-ink-300"
                  }`}>
            <BankBadge bank={b} className="!px-1.5 !py-0.5 !text-[10px]"/>
            <div className="mt-1.5 text-sm font-bold">{BANK_LABELS[b]}</div>
          </button>
        );
      })}
    </div>
  );
}

