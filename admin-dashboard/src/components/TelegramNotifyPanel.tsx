import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Send, RefreshCw, Check, ExternalLink, Loader2, X as XIcon,
} from "lucide-react";

import { apiGet } from "@/api/client";

/** Public discovery payload from /api/v1/notify/info. */
interface NotifyInfo {
  bot_username: string;
  bot_link: string;
  deep_link: string;
  instructions?: string[];
}

/** Per-key linkage state from /api/v1/notify/status-of?key=…. */
interface NotifyStatus {
  linked: boolean;
  chat_id: number | null;
  error?: string;
}

/**
 * Inline panel that lets the user link their {@code sk_} key to the
 * @byme_bank_bot Telegram bot. Used in two places:
 *
 *  • {@code /app/buy} Done step — right after the key is minted.
 *  • {@code /app/test} — directly under the verified-key banner so users
 *    testing an existing key can also link it on the spot.
 *
 * Polls {@code /status-of} every 3s while the panel is mounted and the
 * key is not yet linked. Stops automatically once linked, or after 60s
 * if the user moves on without connecting.
 */
export function TelegramNotifyPanel({
  apiKey,
  variant = "card",
  dismissible = true,
}: {
  /** sk_ key to link. Pass an empty string to render the panel inert. */
  apiKey: string;
  /** "card" = full bordered indigo block, "compact" = smaller inline strip. */
  variant?: "card" | "compact";
  /** When true, show an X button that hides the panel for the session. */
  dismissible?: boolean;
}) {
  const [hidden, setHidden] = useState(false);
  const [pollUntil, setPollUntil] = useState<number>(() => Date.now() + 60_000);

  const validKey = !!apiKey && /^sk_[a-f0-9]{32}$/.test(apiKey);

  const infoQ = useQuery<NotifyInfo>({
    queryKey: ["notify", "info"],
    queryFn: () => apiGet<NotifyInfo>("/api/v1/notify/info"),
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
    enabled: !hidden,
  });

  const statusQ = useQuery<NotifyStatus>({
    queryKey: ["notify", "status", apiKey],
    queryFn: () =>
      apiGet<NotifyStatus>(`/api/v1/notify/status-of?key=${encodeURIComponent(apiKey)}`),
    enabled: validKey && !hidden,
    refetchInterval: (q) => {
      const data = q.state.data;
      if (data?.linked) return false;
      if (Date.now() > pollUntil) return false;
      return 3_000;
    },
    refetchOnWindowFocus: false,
  });

  // If the user clicks "Connect bot →", give them another 60s window of
  // polling so the panel auto-flips to ✓ Linked when they paste the key.
  function bumpPollWindow() {
    setPollUntil(Date.now() + 60_000);
  }

  const linked = statusQ.data?.linked === true;
  const checking = statusQ.isPending && validKey;
  const username = infoQ.data?.bot_username ?? "byme_bank_bot";
  const deepLink = useMemo(() => {
    const fromInfo = infoQ.data?.deep_link;
    if (fromInfo) return fromInfo;
    return `https://t.me/${username}?start=connect`;
  }, [infoQ.data, username]);

  if (hidden) return null;

  const pill = linked
    ? { tone: "bg-emerald-50 ring-emerald-200 text-emerald-700", icon: <Check size={11}/>, text: "Linked" }
    : checking
      ? { tone: "bg-ink-50 ring-ink-200 text-ink-500", icon: <Loader2 size={11} className="animate-spin"/>, text: "Checking…" }
      : { tone: "bg-amber-50 ring-amber-200 text-amber-700", icon: null, text: "Not linked" };

  if (variant === "compact") {
    return (
      <div className="rounded-xl bg-indigo-50/70 ring-1 ring-indigo-100 px-3.5 py-2.5 flex items-center gap-3">
        <Send size={14} className="text-indigo-600 shrink-0"/>
        <div className="flex-1 min-w-0 text-[12px] text-indigo-900">
          {linked ? (
            <>Linked to <strong className="font-semibold">@{username}</strong> · DMs enabled</>
          ) : (
            <>Get a Telegram DM on every payment · <strong>@{username}</strong></>
          )}
        </div>
        {!linked && (
          <a
            href={deepLink}
            target="_blank"
            rel="noopener noreferrer"
            onClick={bumpPollWindow}
            className="inline-flex items-center gap-1 rounded-md bg-indigo-600 hover:bg-indigo-700 px-2.5 py-1 text-[11px] font-semibold text-white transition"
          >
            Connect <ExternalLink size={10}/>
          </a>
        )}
        {linked && (
          <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-700">
            <Check size={11}/> Live
          </span>
        )}
      </div>
    );
  }

  return (
    <section className="relative rounded-2xl ring-1 ring-indigo-200 bg-gradient-to-br from-indigo-50 to-white p-5">
      <header className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2.5 min-w-0">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-indigo-600 text-white shadow-[0_8px_16px_-8px_rgba(79,70,229,0.6)] shrink-0">
            <Send size={14}/>
          </div>
          <div className="min-w-0">
            <h3 className="font-bold text-indigo-950 tracking-tight">
              Telegram payment notifications
            </h3>
            <p className="text-[12px] text-indigo-800/80 mt-0.5">
              Get an instant DM the second a customer pays — no polling, no extra code.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1.5 shrink-0">
          <span className={`inline-flex items-center gap-1 rounded-full ring-1 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.14em] ${pill.tone}`}>
            {pill.icon}
            {pill.text}
          </span>
          {dismissible && (
            <button
              onClick={() => setHidden(true)}
              className="text-indigo-300 hover:text-indigo-700 p-0.5"
              aria-label="Dismiss"
              title="Hide for now"
            >
              <XIcon size={14}/>
            </button>
          )}
        </div>
      </header>

      {linked ? (
        <div className="mt-4 rounded-xl bg-emerald-50 ring-1 ring-emerald-200 px-3.5 py-2.5 flex items-center gap-2.5 text-[13px]">
          <Check size={14} className="text-emerald-700 shrink-0"/>
          <div className="flex-1 min-w-0 text-emerald-900">
            Connected to{" "}
            <strong className="font-semibold">@{username}</strong>
            {statusQ.data?.chat_id && (
              <span className="ml-1.5 font-mono text-[11px] text-emerald-700/80">
                · chat {statusQ.data.chat_id}
              </span>
            )}
            . Every payment will arrive as a DM.
          </div>
        </div>
      ) : (
        <>
          <ol className="mt-4 space-y-2 text-[13px] text-indigo-950">
            <Step n={1}>
              Open <a href={deepLink} target="_blank" rel="noopener noreferrer"
                      onClick={bumpPollWindow}
                      className="font-mono font-semibold text-indigo-700 underline decoration-indigo-300 underline-offset-2 hover:decoration-indigo-700">
                @{username}
              </a>
            </Step>
            <Step n={2}>
              Send <code className="font-mono text-[11px] bg-indigo-100/80 ring-1 ring-indigo-200 px-1.5 py-0.5 rounded">/start</code>
            </Step>
            <Step n={3}>
              Paste your key{" "}
              <code className="font-mono text-[11px] bg-white ring-1 ring-indigo-200 px-1.5 py-0.5 rounded text-indigo-800">
                {validKey ? maskKey(apiKey) : "sk_…"}
              </code>
            </Step>
            <Step n={4}>
              Done — we'll DM you on every payment.
            </Step>
          </ol>

          <div className="mt-4 flex items-center gap-2 flex-wrap">
            <a
              href={deepLink}
              target="_blank"
              rel="noopener noreferrer"
              onClick={bumpPollWindow}
              className="inline-flex items-center gap-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white px-3.5 py-2 text-[13px] font-semibold transition shadow-[0_8px_18px_-10px_rgba(79,70,229,0.7)]"
            >
              <Send size={13}/> Connect bot
              <ExternalLink size={11} className="ml-0.5 opacity-80"/>
            </a>
            <button
              onClick={() => { bumpPollWindow(); statusQ.refetch(); }}
              disabled={statusQ.isFetching}
              className="inline-flex items-center gap-1.5 rounded-xl bg-white ring-1 ring-indigo-200 px-3 py-2 text-[12px] font-semibold text-indigo-800 hover:bg-indigo-50 transition disabled:opacity-50"
            >
              {statusQ.isFetching
                ? <Loader2 size={12} className="animate-spin"/>
                : <RefreshCw size={12}/>}
              I've connected — refresh
            </button>
          </div>
        </>
      )}
    </section>
  );
}

function Step({ n, children }: { n: number; children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-2.5">
      <span className="grid h-5 w-5 place-items-center rounded-full bg-indigo-600 text-white text-[11px] font-bold leading-none shrink-0 mt-0.5">
        {n}
      </span>
      <span className="leading-relaxed">{children}</span>
    </li>
  );
}

function maskKey(k: string) {
  if (!k.startsWith("sk_") || k.length <= 12) return k;
  return `${k.slice(0, 7)}…${k.slice(-4)}`;
}
