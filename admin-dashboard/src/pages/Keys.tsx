import { useState, useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  KeyRound, Loader2, Trash2, Eye, EyeOff, Copy, Zap, Plus, Download,
  Clock, Shield, AlertTriangle, Check, Pencil, Star, X as XIcon, RefreshCw,
} from "lucide-react";
import toast from "react-hot-toast";

import { apiDelete, apiGet, apiPatch, apiPost, ApiError } from "@/api/client";
import { BankBadge, type BankType } from "@/components/BankBadge";
import { useLinkedBanks, BANK_LABELS } from "@/hooks/useLinkedBanks";

/** Row returned by GET /api/v1/me/upstream-key/list. */
interface KeyRow {
  id: string;
  key: string;            // either masked ("sk_xxxx…yyyy") or full when reveal=true
  label?: string | null;
  merchantName?: string;
  planId?: string;
  issuedAt?: string;
  expiresAt?: string;
  revoked: boolean;
  primary: boolean;
  expired: boolean;
  daysRemaining: number;
  banks?: string[];
}

const PLAN_LABEL: Record<string, string> = {
  "1month": "1 month",
  "2month": "2 months",
  "3month": "3 months",
  "1year":  "1 year",
};

export default function Keys() {
  const qc = useQueryClient();
  const [revealId, setRevealId] = useState<string | null>(null);
  const [confirmRow, setConfirmRow] = useState<KeyRow | null>(null);
  const [relinkRow, setRelinkRow] = useState<KeyRow | null>(null);

  const q = useQuery<KeyRow[]>({
    queryKey: ["upstream-key", "list", revealId ?? "none"],
    queryFn: () =>
      apiGet(`/api/v1/me/upstream-key/list?reveal=${revealId ? "true" : "false"}`),
  });

  const buyAnother = useMutation({
    mutationFn: () =>
      apiPost("/api/v1/me/upstream-key/buy-additional", { planId: "1month" }),
    onSuccess: () => {
      toast.success("Additional key issued");
      qc.invalidateQueries({ queryKey: ["upstream-key"] });
    },
    onError: (e: ApiError) => toast.error(e.message ?? "Could not issue key"),
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">API keys</h1>
          <p className="text-ink-500 mt-1">
            Mint, rename, and revoke your{" "}
            <code className="font-mono text-xs bg-ink-100 px-1.5 py-0.5 rounded">sk_</code>{" "}
            keys. Revoking kills the key everywhere — there's no undo.
          </p>
        </div>
        <Link to="/app/buy" className="btn-primary">
          <Plus size={14}/> Buy a new key
        </Link>
      </div>

      {q.isLoading && (
        <div className="grid place-items-center py-20">
          <Loader2 className="animate-spin text-ink-400"/>
        </div>
      )}

      {q.isSuccess && (q.data?.length ?? 0) === 0 && (
        <div className="card card-pad text-center">
          <div className="mx-auto h-14 w-14 rounded-full bg-ink-100 grid place-items-center">
            <KeyRound size={24} className="text-ink-400"/>
          </div>
          <div className="mt-3 font-bold">No keys yet</div>
          <p className="text-sm text-ink-500 mt-1">Run the wizard to mint your first key.</p>
          <Link to="/app/buy" className="btn-primary mt-4 inline-flex">
            <Plus size={14}/> Buy API Key
          </Link>
        </div>
      )}

      {q.isSuccess && (q.data?.length ?? 0) > 0 && (
        <div className="space-y-3">
          {q.data!.map((k) => (
            <KeyCard
              key={k.id}
              row={k}
              revealed={revealId === k.id}
              onReveal={() => setRevealId(revealId === k.id ? null : k.id)}
              onAskRemove={() => setConfirmRow(k)}
              onAskRelink={() => setRelinkRow(k)}
              onRefresh={() => qc.invalidateQueries({ queryKey: ["upstream-key"] })}
            />
          ))}

          <button
            onClick={() => buyAnother.mutate()}
            disabled={buyAnother.isPending}
            className="w-full rounded-2xl border-2 border-dashed border-ink-200 bg-white/50 hover:border-brand-300 hover:bg-brand-50/40 py-5 text-sm font-semibold text-ink-600 hover:text-brand-700 transition flex items-center justify-center gap-2"
          >
            {buyAnother.isPending
              ? <Loader2 size={14} className="animate-spin"/>
              : <Plus size={14}/>}
            Mint another key (uses your linked banks)
          </button>
        </div>
      )}

      {confirmRow && (
        <HardRevokeModal
          row={confirmRow}
          onClose={() => setConfirmRow(null)}
          onDone={() => {
            qc.invalidateQueries({ queryKey: ["upstream-key"] });
            setConfirmRow(null);
          }}
        />
      )}

      {relinkRow && (
        <RelinkBanksModal
          row={relinkRow}
          onClose={() => setRelinkRow(null)}
          onDone={() => {
            qc.invalidateQueries({ queryKey: ["upstream-key"] });
            qc.invalidateQueries({ queryKey: ["merchants"] });
            setRelinkRow(null);
          }}
        />
      )}
    </div>
  );
}

/* ─────────────────────────────────────────────────────── */

/** "Type REVOKE to confirm" modal. Calls DELETE which talks to upstream
 *  /api/owner/revoke_key + deletes the Oracle row + writes revocation_log. */
function HardRevokeModal({
  row, onClose, onDone,
}: {
  row: KeyRow;
  onClose: () => void;
  onDone: () => void;
}) {
  const [phrase, setPhrase] = useState("");
  const armed = phrase === "REVOKE";

  const remove = useMutation({
    mutationFn: () =>
      apiDelete(`/api/v1/me/upstream-key/${row.id}`, { confirm: "REVOKE" }),
    onSuccess: () => { toast.success("Key revoked."); onDone(); },
    onError:   (e: ApiError) => toast.error(e.message ?? "Revoke failed"),
  });

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-ink-900/50 backdrop-blur-sm p-4"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-md rounded-2xl bg-white shadow-2xl ring-1 ring-ink-200 overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-ink-100 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="grid h-7 w-7 place-items-center rounded-full bg-rose-100">
              <AlertTriangle size={14} className="text-rose-700"/>
            </div>
            <div className="text-[15px] font-bold text-ink-900">Permanently revoke this key?</div>
          </div>
          <button onClick={onClose}
                  className="text-ink-400 hover:text-ink-700 p-1 -mr-1"
                  aria-label="Close">
            <XIcon size={16}/>
          </button>
        </div>

        <div className="px-5 py-4 text-[13px] text-ink-700 leading-relaxed space-y-3">
          <p>
            This will kill{" "}
            <code className="font-mono text-[12px] bg-ink-100 px-1.5 py-0.5 rounded">
              {row.key}
            </code>{" "}
            immediately:
          </p>
          <ul className="space-y-1 text-[12px] text-ink-600 list-disc pl-5 marker:text-ink-400">
            <li>Removed from this dashboard</li>
            <li>Removed from the payment bot's database</li>
            <li>
              Every <code className="font-mono text-[11px] bg-ink-100 px-1 py-0.5 rounded">/generate_qr</code>{" "}
              and <code className="font-mono text-[11px] bg-ink-100 px-1 py-0.5 rounded">/api/check_payment</code>{" "}
              call with this key will return 401
            </li>
            <li>
              Any project using this key (production servers, .env files,
              customer integrations) <strong className="text-rose-700">will break</strong>
            </li>
          </ul>
          <p className="text-[12px] text-rose-700 font-semibold">
            This cannot be undone.
          </p>

          <div>
            <label className="text-[11px] uppercase tracking-[0.16em] font-bold text-ink-500 font-mono">
              Type REVOKE to confirm
            </label>
            <input
              autoFocus
              value={phrase}
              onChange={(e) => setPhrase(e.target.value)}
              placeholder="REVOKE"
              spellCheck={false}
              autoCorrect="off"
              autoCapitalize="characters"
              className="mt-1.5 w-full rounded-lg ring-1 ring-ink-200 px-3 py-2 text-[14px] font-mono tracking-wider focus:ring-2 focus:ring-rose-500 focus:outline-none"
            />
          </div>
        </div>

        <div className="px-5 py-3 bg-ink-50/60 border-t border-ink-100 flex items-center justify-end gap-2">
          <button onClick={onClose} className="btn-secondary !py-1.5 !text-xs">
            Cancel
          </button>
          <button
            onClick={() => remove.mutate()}
            disabled={!armed || remove.isPending}
            className="rounded-lg bg-rose-600 hover:bg-rose-700 text-white px-3 py-1.5 text-xs font-bold inline-flex items-center gap-1.5 transition disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {remove.isPending
              ? <><Loader2 size={12} className="animate-spin"/> Revoking…</>
              : <><Trash2 size={12}/> Permanently revoke</>}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────────── */

function KeyCard({ row, revealed, onReveal, onAskRemove, onAskRelink, onRefresh }: {
  row: KeyRow;
  revealed: boolean;
  onReveal: () => void;
  onAskRemove: () => void;
  onAskRelink: () => void;
  onRefresh: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [labelDraft, setLabelDraft] = useState(row.label ?? "");

  const status = row.revoked
    ? { tone: "bg-rose-50 ring-rose-200 text-rose-700",
        icon: <AlertTriangle size={12}/>, text: "Revoked" }
    : row.expired
      ? { tone: "bg-rose-50 ring-rose-200 text-rose-700",
          icon: <AlertTriangle size={12}/>, text: "Expired" }
      : row.daysRemaining <= 7
        ? { tone: "bg-amber-50 ring-amber-200 text-amber-800",
            icon: <Clock size={12}/>, text: `Expires in ${row.daysRemaining} day${row.daysRemaining === 1 ? "" : "s"}` }
        : { tone: "bg-accent-50 ring-accent-200 text-accent-800",
            icon: <Clock size={12}/>, text: `Expires in ${row.daysRemaining} days` };

  const rename = useMutation({
    mutationFn: (label: string) =>
      apiPatch(`/api/v1/me/upstream-key/${row.id}`, { label }),
    onSuccess: () => {
      toast.success("Renamed");
      setEditing(false);
      onRefresh();
    },
    onError: (e: ApiError) => toast.error(e.message ?? "Rename failed"),
  });

  const makePrimary = useMutation({
    mutationFn: () => apiPost(`/api/v1/me/upstream-key/${row.id}/primary`),
    onSuccess: () => { toast.success("Primary key set"); onRefresh(); },
    onError: (e: ApiError) => toast.error(e.message ?? "Failed"),
  });

  function copyKey() {
    if (!revealed) {
      toast("Click the eye icon to reveal first");
      return;
    }
    navigator.clipboard.writeText(row.key);
    toast.success("Copied");
  }

  return (
    <div className={`card card-pad ${row.revoked ? "opacity-70" : ""}`}>
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex items-start gap-2 min-w-0 flex-1">
          <Shield size={18} className={row.primary ? "text-brand-600 mt-0.5" : "text-ink-400 mt-0.5"}/>
          <div className="min-w-0 flex-1">
            {editing ? (
              <form
                onSubmit={(e) => { e.preventDefault(); rename.mutate(labelDraft); }}
                className="flex gap-1.5 items-center"
              >
                <input
                  className="input !py-1 !text-sm flex-1"
                  value={labelDraft}
                  onChange={(e) => setLabelDraft(e.target.value)}
                  autoFocus
                  placeholder="e.g. Production"
                  maxLength={80}
                />
                <button type="submit" className="btn-primary !py-1.5 !px-2.5 !text-xs"
                        disabled={rename.isPending}>
                  {rename.isPending ? <Loader2 size={12} className="animate-spin"/> : <Check size={12}/>}
                </button>
                <button type="button" onClick={() => { setEditing(false); setLabelDraft(row.label ?? ""); }}
                        className="btn-ghost !py-1.5 !px-2 !text-xs">Cancel</button>
              </form>
            ) : (
              <div className="flex items-center gap-2 flex-wrap">
                <span className="font-bold truncate">{row.label || "Untitled key"}</span>
                {row.primary && (
                  <span className="badge bg-brand-100 text-brand-800 ring-1 ring-brand-200/60 !text-[10px]">
                    <Star size={10}/> primary
                  </span>
                )}
                <button onClick={() => setEditing(true)}
                        className="text-ink-400 hover:text-ink-700">
                  <Pencil size={12}/>
                </button>
              </div>
            )}
            <div className="text-xs text-ink-500 mt-0.5 truncate">
              {row.merchantName ?? "—"}
              <span className="mx-1.5 text-ink-300">·</span>
              {PLAN_LABEL[row.planId ?? ""] ?? row.planId}
            </div>
          </div>
        </div>
        <div className={`shrink-0 rounded-xl ring-1 px-3 py-1.5 text-xs font-bold flex items-center gap-1 ${status.tone}`}>
          {status.icon} {status.text}
        </div>
      </div>

      {/* Key value */}
      <div className="mt-3 rounded-xl bg-ink-900 p-3">
        <div className="flex items-center gap-2">
          <code className="flex-1 font-mono text-sm text-emerald-300 break-all">
            {row.key}
          </code>
          <button onClick={onReveal}
                  className="text-ink-400 hover:text-white p-1.5 transition"
                  title={revealed ? "Hide" : "Reveal"}>
            {revealed ? <EyeOff size={14}/> : <Eye size={14}/>}
          </button>
          <button onClick={copyKey} className="text-ink-400 hover:text-white p-1.5 transition"
                  title="Copy">
            <Copy size={14}/>
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="mt-3 grid grid-cols-2 gap-2 text-[11px]">
        <Stat label="Issued"  value={row.issuedAt  ? new Date(row.issuedAt).toLocaleString()  : "—"}/>
        <Stat label="Expires" value={row.expiresAt ? new Date(row.expiresAt).toLocaleString() : "—"}/>
      </div>

      {/* Actions */}
      <div className="mt-3 flex items-center justify-end gap-1.5 flex-wrap">
        {!row.revoked && !row.expired && (
          <Link to="/app/test" className="btn-ghost !py-1.5 !text-xs">
            <Zap size={12}/> Test
          </Link>
        )}
        <button
          onClick={async () => {
            // Plain old "open URL in new tab" — no blob fetch, no auth
            // gymnastics. Just unmask the key if needed, then go.
            try {
              let key: string | null = /^sk_[a-f0-9]{32}$/.test(row.key) ? row.key : null;
              if (!key) {
                const fresh = await apiGet<KeyRow[]>(
                    "/api/v1/me/upstream-key/list?reveal=true");
                const match = fresh.find((k) => k.id === row.id);
                if (match && /^sk_[a-f0-9]{32}$/.test(match.key)) {
                  key = match.key;
                }
              }
              if (!key) {
                toast.error("Couldn't unmask this key — try again");
                return;
              }
              const url = `https://apicheckpayment.onrender.com/docs/key.pdf?key=${encodeURIComponent(key)}`;
              window.open(url, "_blank", "noopener,noreferrer");
            } catch {
              toast.error("Couldn't open the PDF — try again");
            }
          }}
          className="btn-ghost !py-1.5 !text-xs"
          title="Open integration guide PDF in a new tab"
        >
          <Download size={12}/> PDF
        </button>
        {!row.revoked && !row.expired && !row.primary && (
          <button onClick={() => makePrimary.mutate()}
                  disabled={makePrimary.isPending}
                  className="btn-ghost !py-1.5 !text-xs">
            <Star size={12}/> Make primary
          </button>
        )}
        {!row.revoked && !row.expired && (
          <button
            onClick={onAskRelink}
            className="btn-ghost !py-1.5 !text-xs"
            title="Revoke this key and mint a new one with a corrected bank list"
          >
            <RefreshCw size={12}/> Re-link banks
          </button>
        )}
        <button
          onClick={onAskRemove}
          className="btn-ghost !py-1.5 !text-xs text-rose-600 hover:!bg-rose-50"
        >
          <Trash2 size={12}/> Remove
        </button>
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-ink-50 ring-1 ring-ink-100 px-2.5 py-1.5">
      <div className="text-[9px] uppercase tracking-[0.16em] font-mono text-ink-400">
        {label}
      </div>
      <div className="text-ink-900 font-mono text-[10px] truncate">{value}</div>
    </div>
  );
}

/* ─────────────────────────────────────────────────────── */

/**
 * "Re-link banks" modal — repair flow for keys that got polluted by the
 * pre-fix registration leak (where the wizard silently included every
 * active merchant on the user, instead of just the QRs they uploaded
 * for THAT key).
 *
 * Trade-off the user has to acknowledge:
 *   • Old key is hard-revoked upstream (every /generate_qr + /api/check_payment
 *     call against it returns 401 immediately).
 *   • A NEW sk_ is minted scoped to the banks they tick here.
 *   • Anything using the old key needs to switch over.
 *
 * Why a new key vs. patching: the upstream bot has no "patch banks"
 * endpoint — see the prompt's §3.1. Revoke + re-issue is the cleanest path
 * available right now.
 */
function RelinkBanksModal({ row, onClose, onDone }: {
  row: KeyRow;
  onClose: () => void;
  onDone: () => void;
}) {
  // Live linked banks for this key — the source of truth for the picker.
  const linked = useLinkedBanks(row.id);
  const [selected, setSelected] = useState<Set<BankType>>(new Set());

  // Default the selection to "what's currently linked" so the user can
  // just untick the unwanted ones.
  useEffect(() => {
    if (linked.banks.length && selected.size === 0) {
      setSelected(new Set(linked.banks));
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linked.banks.join(",")]);

  function toggle(b: BankType) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(b)) next.delete(b);
      else next.add(b);
      return next;
    });
  }

  const rebuild = useMutation({
    mutationFn: () =>
      apiPost<{ key: string; newKeyId: string }>(
          `/api/v1/me/upstream-key/${row.id}/rebuild-banks`,
          { banks: Array.from(selected).map((b) => b.toLowerCase()) },
      ),
    onSuccess: () => {
      toast.success("New key issued — old one revoked");
      onDone();
    },
    onError: (e: ApiError) => toast.error(e.message ?? "Re-link failed"),
  });

  const armed = selected.size > 0 && !rebuild.isPending;

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-ink-950/55 backdrop-blur-sm p-4"
         onClick={onClose}>
      <div className="w-full max-w-md rounded-2xl bg-white shadow-soft ring-1 ring-ink-200 overflow-hidden"
           onClick={(e) => e.stopPropagation()}>
        <div className="px-5 py-4 flex items-start justify-between gap-3 border-b border-ink-100">
          <div className="flex items-center gap-2">
            <div className="grid h-7 w-7 place-items-center rounded-full bg-amber-100">
              <RefreshCw size={14} className="text-amber-700"/>
            </div>
            <div className="text-[15px] font-bold text-ink-900">Re-link banks</div>
          </div>
          <button onClick={onClose}
                  className="text-ink-400 hover:text-ink-700 transition">
            <XIcon size={16}/>
          </button>
        </div>

        <div className="px-5 py-4 space-y-3">
          <div className="rounded-xl bg-amber-50 ring-1 ring-amber-200 px-3.5 py-3 text-[12px] text-amber-900">
            <div className="font-bold">Heads up — this issues a NEW key</div>
            <p className="mt-0.5 text-amber-800/90 leading-snug">
              Your current <code className="font-mono text-[11px] bg-amber-100 px-1 rounded">sk_</code> will
              be hard-revoked. Anything using it stops working immediately.
              You'll get a fresh key scoped to ONLY the banks you tick below.
            </p>
          </div>

          <div className="text-[11px] uppercase tracking-[0.16em] font-bold text-ink-500 font-mono">
            Keep these banks
          </div>

          {linked.loading && (
            <div className="grid grid-cols-2 gap-2">
              {[0, 1].map((i) => (
                <div key={i} className="h-[58px] rounded-xl border-2 border-ink-200 bg-ink-50 animate-pulse"/>
              ))}
            </div>
          )}

          {!linked.loading && !linked.valid && (
            <div className="rounded-xl bg-rose-50 ring-1 ring-rose-200 px-3 py-2.5 text-xs text-rose-900">
              Upstream doesn't recognise this key — nothing to re-link.
            </div>
          )}

          {!linked.loading && linked.valid && linked.banks.length === 0 && (
            <div className="rounded-xl bg-ink-50 ring-1 ring-ink-200 px-3 py-2.5 text-xs text-ink-700">
              This key has no banks linked upstream. Use the Buy wizard to mint
              a fresh one with the banks you want.
            </div>
          )}

          {!linked.loading && linked.valid && linked.banks.length > 0 && (
            <div className={`grid gap-2 ${linked.banks.length === 1 ? "grid-cols-1" : "grid-cols-2"}`}>
              {linked.banks.map((b) => {
                const sel = selected.has(b);
                return (
                  <button type="button" key={b}
                          onClick={() => toggle(b)}
                          className={`flex items-center gap-2.5 rounded-xl border-2 px-3 py-2.5 text-left transition ${
                            sel
                              ? "border-brand-500 bg-brand-50 ring-2 ring-brand-500/20"
                              : "border-ink-200 hover:border-ink-300"
                          }`}>
                    <div className={`grid h-4 w-4 place-items-center rounded-md transition ${
                      sel ? "bg-brand-600 text-white" : "ring-1 ring-ink-300 bg-white"
                    }`}>
                      {sel && <Check size={11} strokeWidth={3}/>}
                    </div>
                    <BankBadge bank={b} className="!px-1.5 !py-0.5 !text-[10px]"/>
                    <span className="text-[12px] font-bold text-ink-900 truncate">
                      {BANK_LABELS[b]}
                    </span>
                  </button>
                );
              })}
            </div>
          )}

          <div className="text-[11px] text-ink-500">
            {selected.size === 0
              ? "Tick at least one bank."
              : selected.size === 1
                ? "1 bank will be linked to the new key."
                : `${selected.size} banks will be linked to the new key.`}
          </div>
        </div>

        <div className="px-5 py-3 bg-ink-50/60 border-t border-ink-100 flex items-center justify-end gap-2">
          <button onClick={onClose}
                  className="rounded-lg px-3 py-1.5 text-xs font-semibold text-ink-600 hover:text-ink-900 hover:bg-ink-100 transition">
            Cancel
          </button>
          <button
            onClick={() => rebuild.mutate()}
            disabled={!armed}
            className="rounded-lg bg-ink-900 hover:bg-ink-950 text-white px-3 py-1.5 text-xs font-bold inline-flex items-center gap-1.5 transition disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {rebuild.isPending
              ? <><Loader2 size={12} className="animate-spin"/> Rebuilding…</>
              : <><RefreshCw size={12}/> Revoke and re-issue</>}
          </button>
        </div>
      </div>
    </div>
  );
}
