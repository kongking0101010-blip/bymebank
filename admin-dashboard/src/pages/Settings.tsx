import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  User, Mail, Phone, Building, Shield, Trash2, KeyRound, Loader2, Copy,
  RefreshCw, Eye, EyeOff, Download, Zap, Check, Clock,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import toast from "react-hot-toast";

import { apiDelete, apiGet, apiPost, ApiError } from "@/api/client";
import { useAuthStore } from "@/store/auth";

interface UpstreamKey {
  hasKey: boolean;
  key?: string;
  expiresAt?: string;
  issuedAt?: string;
  expired?: boolean;
}

interface TestQrResult {
  success: boolean;
  md5: string;
  qr_image: string;
  qr_string: string;
  bank: string;
  merchant_name?: string;
}

interface PaymentCheck {
  success: boolean;
  status: "PAID" | "UNPAID" | "ERROR" | string;
  amount?: number;
  from?: string;
}

type PollState =
  | { phase: "idle" }
  | { phase: "polling"; attempt: number; total: number }
  | { phase: "paid"; from?: string; amount?: number }
  | { phase: "timeout" };

export default function Settings() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const qc = useQueryClient();
  const [reveal, setReveal] = useState(false);
  const [testQr, setTestQr] = useState<TestQrResult | null>(null);
  const [pollState, setPollState] = useState<PollState>({ phase: "idle" });
  const pollAbort = useRef<AbortController | null>(null);

  // Cancel any in-flight polling when the test QR is dismissed or component unmounts.
  useEffect(() => () => pollAbort.current?.abort(), []);

  const keyQ = useQuery<UpstreamKey>({
    queryKey: ["upstream-key", reveal ? "reveal" : "masked"],
    queryFn: () => apiGet(`/api/v1/me/upstream-key?reveal=${reveal}`),
  });

  const rotate = useMutation({
    mutationFn: () => apiPost<UpstreamKey>("/api/v1/me/upstream-key/refresh", { days: 30 }),
    onSuccess: () => {
      toast.success("New key issued");
      setReveal(true);
      qc.invalidateQueries({ queryKey: ["upstream-key"] });
    },
    onError: (e: ApiError) => toast.error(e.message),
  });

  const revoke = useMutation({
    mutationFn: () => apiDelete("/api/v1/me/upstream-key"),
    onSuccess: () => {
      toast.success("Key cleared");
      pollAbort.current?.abort();
      setTestQr(null);
      setPollState({ phase: "idle" });
      qc.invalidateQueries({ queryKey: ["upstream-key"] });
    },
  });

  const test = useMutation({
    mutationFn: () => apiPost<TestQrResult>("/api/v1/me/upstream-key/test", {}),
    onSuccess: (r) => {
      setTestQr(r);
      toast.success("Test QR generated — scan with Bakong app to verify");
      // Auto-poll PAID for up to 30s (10 polls × 3s)
      startPaidPoll(r.md5);
    },
    onError: (e: ApiError) => toast.error(e.message ?? "Test failed"),
  });

  function startPaidPoll(md5: string) {
    pollAbort.current?.abort();
    const ctrl = new AbortController();
    pollAbort.current = ctrl;
    const TOTAL = 10;
    const STEP_MS = 3_000;

    setPollState({ phase: "polling", attempt: 0, total: TOTAL });

    const tick = async (i: number) => {
      if (ctrl.signal.aborted) return;
      try {
        const cp = await apiGet<PaymentCheck>(
            `/api/v1/me/upstream-key/check-payment?md5=${encodeURIComponent(md5)}`);
        if (ctrl.signal.aborted) return;
        if (cp.status === "PAID") {
          setPollState({ phase: "paid", amount: cp.amount, from: cp.from });
          toast.success(`Paid! ${cp.amount ?? ""} from ${cp.from ?? "—"}`);
          return;
        }
      } catch {
        // ignore — keep polling
      }
      if (i + 1 >= TOTAL) {
        setPollState({ phase: "timeout" });
        return;
      }
      setPollState({ phase: "polling", attempt: i + 1, total: TOTAL });
      setTimeout(() => tick(i + 1), STEP_MS);
    };
    tick(0);
  }

  function dismissTestQr() {
    pollAbort.current?.abort();
    setTestQr(null);
    setPollState({ phase: "idle" });
  }

  function copyKey() {
    if (!keyQ.data?.key) return;
    navigator.clipboard.writeText(keyQ.data.key);
    toast.success("Copied!");
  }

  /**
   * Open the upstream PDF guide for the user's sk_ key in a new tab.
   *
   * <p>The Settings page renders the key MASKED by default (sk_c…9683)
   * which would fail the strict {@code sk_[a-f0-9]\{32\}} check in the
   * URL. Fetching with {@code ?reveal=true} side-steps the toggle so the
   * user doesn't have to click the eye icon first.
   */
  async function downloadPdf() {
    if (!keyQ.data?.hasKey) return;
    let key = keyQ.data.key;
    if (!key || !/^sk_[a-f0-9]{32}$/.test(key)) {
      try {
        const fresh = await apiGet<UpstreamKey>("/api/v1/me/upstream-key?reveal=true");
        key = fresh.key;
      } catch {
        toast.error("Couldn't unmask your key — try again");
        return;
      }
    }
    if (!key || !/^sk_[a-f0-9]{32}$/.test(key)) {
      toast.error("Bad key format — re-issue from Buy API Key",
          { id: "bad-key-format" });
      return;
    }
    const url = `https://apicheckpayment.onrender.com/docs/key.pdf?key=${encodeURIComponent(key)}`;
    window.open(url, "_blank", "noopener,noreferrer");
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Settings</h1>
        <p className="text-ink-500 mt-1">Manage your profile, API key, and security.</p>
      </div>

      <div className="grid lg:grid-cols-2 gap-6">
        {/* Profile */}
        <div className="card card-pad">
          <div className="flex items-center gap-2 mb-4">
            <User size={16} className="text-brand-600"/>
            <div className="text-sm font-bold">Profile</div>
          </div>
          <div className="space-y-3">
            <Field icon={User}  label="Full name" value={user?.fullName ?? ""}/>
            <Field icon={Mail}  label="Email"     value={user?.email ?? ""}/>
            <Field icon={Phone} label="Phone"     value="—"/>
            <Field icon={Building} label="Company" value="—"/>
            <Field icon={Shield}label="Role"      value={user?.role ?? "USER"}/>
          </div>
        </div>

        {/* API key */}
        <div className="card card-pad">
          <div className="flex items-center gap-2 mb-4">
            <KeyRound size={16} className="text-brand-600"/>
            <div className="text-sm font-bold">API Key</div>
          </div>

          {keyQ.isLoading ? (
            <div className="text-center py-6 text-ink-400">
              <Loader2 className="inline animate-spin" size={18}/>
            </div>
          ) : keyQ.data?.hasKey ? (
            <>
              <div className="rounded-2xl bg-ink-900 p-4">
                <div className="flex items-center gap-2">
                  <code className="flex-1 font-mono text-sm text-emerald-300 break-all">
                    {keyQ.data.key}
                  </code>
                  <button onClick={() => setReveal(!reveal)}
                          className="text-ink-400 hover:text-white p-1.5">
                    {reveal ? <EyeOff size={14}/> : <Eye size={14}/>}
                  </button>
                  <button onClick={copyKey} className="text-ink-400 hover:text-white p-1.5">
                    <Copy size={14}/>
                  </button>
                </div>
              </div>

              <div className="mt-3 grid grid-cols-2 gap-3 text-[11px]">
                <Stat label="Issued" value={keyQ.data.issuedAt
                  ? new Date(keyQ.data.issuedAt).toLocaleString() : "—"}/>
                <Stat label="Expires" value={keyQ.data.expiresAt
                  ? new Date(keyQ.data.expiresAt).toLocaleString() : "—"}/>
              </div>

              <div className="mt-4 flex items-center justify-end gap-2 flex-wrap">
                <button onClick={() => test.mutate()}
                        disabled={test.isPending}
                        className="btn-secondary">
                  {test.isPending
                    ? <Loader2 size={14} className="animate-spin"/>
                    : <><Zap size={14}/> Test key</>}
                </button>
                <button onClick={downloadPdf} className="btn-secondary">
                  <Download size={14}/> Download PDF guide
                </button>
                <button onClick={() => revoke.mutate()}
                        disabled={revoke.isPending}
                        className="btn-ghost text-red-600">
                  <Trash2 size={14}/> Forget
                </button>
                <button onClick={() => rotate.mutate()}
                        disabled={rotate.isPending}
                        className="btn-primary">
                  {rotate.isPending
                    ? <Loader2 size={14} className="animate-spin"/>
                    : <><RefreshCw size={14}/> Rotate</>}
                </button>
              </div>

              {testQr && (
                <div className="mt-4 rounded-2xl border border-accent-300 bg-accent-50/40 p-4">
                  <div className="flex items-center gap-2 text-sm font-semibold text-accent-800">
                    <Zap size={14}/> Test QR — 0.01 KHR · Bakong
                  </div>
                  <p className="text-xs text-ink-600 mt-1">
                    Scan with your Bakong app. Money will hit your linked account.
                    MD5: <code className="font-mono">{testQr.md5.slice(0, 16)}…</code>
                  </p>
                  <div className="mt-3 grid sm:grid-cols-[160px_1fr] gap-4 items-start">
                    <img src={testQr.qr_image} alt="Test QR"
                         className="w-40 h-40 rounded-lg bg-white ring-1 ring-ink-200 object-contain"/>
                    <div className="space-y-2">
                      <div className="text-xs">
                        <div className="text-ink-500 font-semibold">Merchant</div>
                        <div className="text-ink-900 font-medium">{testQr.merchant_name ?? "—"}</div>
                      </div>

                      {/* Poll status */}
                      <div className="rounded-lg bg-white ring-1 ring-ink-200 p-2.5 text-xs">
                        <div className="text-ink-500 font-semibold mb-1">Payment status</div>
                        {pollState.phase === "polling" && (
                          <div className="flex items-center gap-2 text-ink-700">
                            <Loader2 size={12} className="animate-spin text-brand-600"/>
                            Polling… {pollState.attempt + 1}/{pollState.total}
                          </div>
                        )}
                        {pollState.phase === "paid" && (
                          <div className="flex items-center gap-2 text-accent-700 font-semibold">
                            <Check size={14}/> Paid
                            {pollState.amount != null && <span> · {pollState.amount}</span>}
                            {pollState.from && <span className="text-ink-500"> from {pollState.from}</span>}
                          </div>
                        )}
                        {pollState.phase === "timeout" && (
                          <div className="flex items-center gap-2 text-ink-600">
                            <Clock size={12}/>
                            No payment received in 30s — that's expected for a 0.01 KHR sandbox check.
                          </div>
                        )}
                        {pollState.phase === "idle" && (
                          <div className="text-ink-500">Idle</div>
                        )}
                      </div>

                      <div className="text-xs">
                        <div className="text-ink-500 font-semibold">QR string</div>
                        <code className="block bg-white ring-1 ring-ink-200 rounded p-1.5 text-[10px] font-mono break-all max-h-20 overflow-y-auto">
                          {testQr.qr_string}
                        </code>
                      </div>
                      <div className="flex gap-2">
                        <button onClick={dismissTestQr} className="btn-ghost text-xs">Dismiss</button>
                        {pollState.phase === "timeout" && (
                          <button onClick={() => startPaidPoll(testQr.md5)}
                                  className="btn-secondary text-xs">
                            <RefreshCw size={12}/> Poll again
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </>
          ) : (
            <div className="rounded-xl bg-amber-50 ring-1 ring-amber-200 p-4 text-sm text-amber-800">
              No API key yet. Go to <a href="/app" className="font-bold underline">Buy API Key</a> to mint one.
            </div>
          )}
        </div>

        {/* Security */}
        <div className="card card-pad">
          <div className="flex items-center gap-2 mb-4">
            <Shield size={16} className="text-brand-600"/>
            <div className="text-sm font-bold">Security</div>
          </div>
          <p className="text-sm text-ink-500">
            Two-factor authentication, IP allow-lists, and session controls are coming soon.
          </p>
          <button className="btn-secondary mt-4" disabled>Change password (coming soon)</button>
        </div>

        {/* Account actions */}
        <div className="card card-pad">
          <div className="flex items-center gap-2 mb-4">
            <Trash2 size={16} className="text-red-600"/>
            <div className="text-sm font-bold">Account actions</div>
          </div>
          <p className="text-sm text-ink-500">
            Sign out of this browser. Your API key will keep working from your server.
          </p>
          <button onClick={() => { logout(); window.location.href = "/"; }}
                  className="btn-secondary mt-4 text-red-600">
            Sign out of this device
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ icon: Icon, label, value }: { icon: any; label: string; value: string }) {
  return (
    <div>
      <label className="label flex items-center gap-1.5">
        <Icon size={12}/> {label}
      </label>
      <input className="input" value={value} readOnly/>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-ink-50 ring-1 ring-ink-100 px-3 py-2">
      <div className="text-ink-500 font-semibold">{label}</div>
      <div className="text-ink-800 mt-0.5">{value}</div>
    </div>
  );
}
