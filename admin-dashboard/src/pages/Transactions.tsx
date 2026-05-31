import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Receipt, Search, Loader2 } from "lucide-react";

import { apiGet } from "@/api/client";
import { BankBadge, BankType } from "@/components/BankBadge";
import { StatusBadge } from "@/components/StatusBadge";

type StatusFilter = "ALL" | "PENDING" | "PAID" | "UNPAID" | "EXPIRED";

interface TxRow {
  id: string;
  md5: string;
  bank: string;
  amount: number;
  currency: string;
  status: StatusFilter;
  paidAt?: string | null;
  paidFrom?: string | null;
  createdAt: string;
}
interface Page {
  page: number; size: number; total: number; totalPages: number;
  items: TxRow[];
}

export default function Transactions() {
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<StatusFilter>("ALL");
  const [page, setPage] = useState(0);

  const list = useQuery<Page>({
    queryKey: ["me", "transactions", filter, page],
    queryFn: () => apiGet(
      `/api/v1/me/transactions?page=${page}&size=20${
        filter === "ALL" ? "" : `&status=${filter}`}`),
    refetchInterval: 8_000,
  });

  const visible = (list.data?.items ?? []).filter((r) =>
    q === "" || r.md5.includes(q.toLowerCase()) || r.bank.includes(q.toUpperCase()));

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Transactions</h1>
        <p className="text-ink-500 mt-1">Every payment generated through your account.</p>
      </div>

      <div className="card card-pad">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[200px]">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-400"/>
            <input className="input pl-9" placeholder="Search md5 or bank…"
                   value={q} onChange={(e) => setQ(e.target.value)}/>
          </div>
          <div className="flex items-center gap-1 rounded-xl bg-ink-100 p-1 flex-wrap">
            {(["ALL", "PAID", "PENDING", "UNPAID", "EXPIRED"] as const).map((s) => (
              <button key={s}
                      onClick={() => { setFilter(s); setPage(0); }}
                      className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition ${
                        filter === s ? "bg-white shadow-soft text-ink-900"
                                    : "text-ink-500 hover:text-ink-800"
                      }`}>{s}</button>
            ))}
          </div>
        </div>

        <div className="mt-4 table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>MD5</th>
                <th>Bank</th>
                <th>Amount</th>
                <th>Status</th>
                <th>From</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {list.isLoading && (
                <tr><td colSpan={6} className="text-center py-12 text-ink-400">
                  <Loader2 className="inline animate-spin"/>
                </td></tr>
              )}
              {list.isSuccess && visible.length === 0 && (
                <tr><td colSpan={6} className="text-center py-16 text-ink-500">
                  <Receipt className="mx-auto mb-3 text-ink-300" size={28}/>
                  No transactions yet. Generate a test QR to start.
                </td></tr>
              )}
              {visible.map((r) => (
                <tr key={r.md5}>
                  <td className="font-mono text-xs">{r.md5.slice(0, 16)}…</td>
                  <td><BankBadge bank={r.bank.toUpperCase() as BankType}/></td>
                  <td className="font-semibold">
                    {r.currency === "USD"
                      ? `$${Number(r.amount).toFixed(2)}`
                      : `${Number(r.amount).toLocaleString()} ៛`}
                  </td>
                  <td><StatusBadge status={r.status as any}/></td>
                  <td className="font-mono text-xs text-ink-500">{r.paidFrom ?? "—"}</td>
                  <td className="text-ink-500">{new Date(r.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {list.data && list.data.totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between text-xs text-ink-500">
            <span>Page {page + 1} of {list.data.totalPages} · {list.data.total} total</span>
            <div className="flex gap-2">
              <button className="btn-ghost"
                      disabled={page === 0}
                      onClick={() => setPage(p => Math.max(0, p - 1))}>← Prev</button>
              <button className="btn-ghost"
                      disabled={page + 1 >= list.data.totalPages}
                      onClick={() => setPage(p => p + 1)}>Next →</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
