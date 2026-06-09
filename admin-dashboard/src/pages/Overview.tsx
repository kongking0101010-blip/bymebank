import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  KeyRound, Sparkles, Receipt, TrendingUp, AlertTriangle, ArrowRight, Loader2,
} from "lucide-react";
import { apiGet } from "@/api/client";
import { BankBadge, BankType } from "@/components/BankBadge";

interface OverviewData {
  activeKey: {
    apiKey: string;
    planId: string;
    planLabel: string;
    expiresAt: string;
    daysRemaining: number;
    merchantName: string;
  } | null;
  tx7d:  { paidCount: number; paidUsd: number; paidKhr: number };
  tx30d: { paidCount: number; paidUsd: number; paidKhr: number };
  banksLinked: string[];
}

export default function Overview() {
  const q = useQuery<OverviewData>({
    queryKey: ["me", "overview"],
    queryFn: () => apiGet("/api/v1/me/overview"),
    refetchInterval: 30_000,
    // Keep showing the last data while refetching so the page never blanks out.
    placeholderData: (prev) => prev,
  });

  // Note: we intentionally do NOT block the whole page on a spinner. The KPI
  // cards and key card all handle missing data with sensible fallbacks, so we
  // render the full layout immediately and let values fill in. This makes the
  // dashboard feel instant instead of staring at a centered loader.
  const d = q.data;
  const days = d?.activeKey?.daysRemaining ?? -1;
  const expiryColor =
      days < 0  ? "bg-red-50 ring-red-200 text-red-700"
    : days <= 7 ? "bg-amber-50 ring-amber-200 text-amber-800"
                : "bg-accent-50 ring-accent-200 text-accent-800";

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight flex items-center gap-2">
          Overview
          {q.isFetching && <Loader2 className="animate-spin text-ink-400" size={16}/>}
        </h1>
        <p className="text-ink-500 mt-1">Your Byme Bank workspace at a glance.</p>
      </div>

      {/* Top KPI strip */}
      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Kpi icon={KeyRound}  label="Active key"
             value={d?.activeKey ? d.activeKey.apiKey : "None"}
             sub={d?.activeKey ? d.activeKey.planLabel : "Mint one in Buy API Key"}
             accent="brand"/>
        <Kpi icon={TrendingUp} label="Paid (7d)"
             value={`$${num(d?.tx7d.paidUsd)}`}
             sub={`${d?.tx7d.paidCount ?? 0} txns`}
             accent="accent"/>
        <Kpi icon={Receipt}    label="Paid (30d)"
             value={`$${num(d?.tx30d.paidUsd)}`}
             sub={`${d?.tx30d.paidCount ?? 0} txns`}
             accent="brand"/>
        <Kpi icon={Sparkles}   label="Banks linked"
             value={String(d?.banksLinked?.length ?? 0)}
             sub={(d?.banksLinked ?? []).join(" · ").toUpperCase() || "—"}
             accent="accent"/>
      </div>

      {/* Active key card */}
      {d?.activeKey ? (
        <div className="card card-pad">
          <div className="flex items-start justify-between gap-4 flex-wrap">
            <div>
              <div className="text-xs font-bold uppercase tracking-widest text-ink-500">
                Your active key
              </div>
              <code className="block mt-1 font-mono text-lg font-bold text-ink-900">
                {d.activeKey.apiKey}
              </code>
              <div className="mt-1 text-sm text-ink-500">
                Plan <strong>{d.activeKey.planLabel}</strong> · Merchant <strong>{d.activeKey.merchantName}</strong>
              </div>
            </div>
            <div className={`rounded-xl ring-1 px-3 py-2 text-xs font-bold ${expiryColor}`}>
              {days < 0
                ? <><AlertTriangle size={12} className="inline mb-0.5 mr-1"/> Expired</>
                : `Expires in ${days} day${days === 1 ? "" : "s"}`}
            </div>
          </div>

          <div className="mt-4 flex gap-2 flex-wrap">
            <Link to="/app/test" className="btn-secondary">Test this key</Link>
            <Link to="/app/keys" className="btn-secondary">Manage keys</Link>
            <Link to="/app/transactions" className="btn-primary">
              See transactions <ArrowRight size={14}/>
            </Link>
          </div>
        </div>
      ) : (
        <div className="card card-pad text-center">
          <div className="mx-auto h-14 w-14 rounded-full bg-ink-100 grid place-items-center">
            <KeyRound size={24} className="text-ink-400"/>
          </div>
          <div className="mt-3 font-bold">No API key yet</div>
          <p className="text-sm text-ink-500 mt-1">Mint one in 60 seconds.</p>
          <Link to="/app/buy" className="btn-primary mt-4 inline-flex">
            Buy API Key <ArrowRight size={14}/>
          </Link>
        </div>
      )}

      {(d?.banksLinked?.length ?? 0) > 0 && (
        <div className="card card-pad">
          <div className="text-sm font-bold mb-3">Linked banks</div>
          <div className="flex flex-wrap gap-2">
            {d!.banksLinked.map((b) => (
              <BankBadge key={b} bank={b.toUpperCase() as BankType}/>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function num(v: number | null | undefined): string {
  if (v == null) return "0.00";
  return Number(v).toFixed(2);
}

function Kpi({ icon: Icon, label, value, sub, accent }: {
  icon: any; label: string; value: string; sub: string; accent: "brand" | "accent";
}) {
  const tone = accent === "brand"
    ? "from-brand-500 to-brand-700"
    : "from-accent-500 to-accent-700";
  return (
    <div className="card card-pad relative overflow-hidden">
      <div className={`absolute -top-16 -right-16 h-40 w-40 rounded-full bg-gradient-to-br ${tone} opacity-10 blur-2xl`}/>
      <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-ink-500">
        <Icon size={14}/> {label}
      </div>
      <div className="mt-1.5 text-2xl font-extrabold truncate">{value}</div>
      <div className="text-[11px] text-ink-500 mt-0.5 truncate">{sub}</div>
    </div>
  );
}
