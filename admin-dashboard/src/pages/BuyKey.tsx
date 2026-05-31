import { ChangeEvent, useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import {
  ArrowRight, ArrowLeft, Check, Loader2, Copy, Sparkles,
  X as XIcon, UploadCloud, Image as ImageIcon,
  Phone, Hash, KeyRound, Save, Download,
  Eye, EyeOff, Shield, Clock, Zap, Plus, AlertTriangle,
  RefreshCw, QrCode as QrCodeIcon,
} from "lucide-react";
import toast from "react-hot-toast";
import { motion, AnimatePresence } from "framer-motion";

import api, { apiGet, apiPost, ApiError } from "@/api/client";
import { BankBadge, BankType } from "@/components/BankBadge";
import { BankLogo } from "@/components/BankLogo";
import { KhqrReceiptCard } from "@/components/KhqrReceiptCard";
import { TelegramNotifyPanel } from "@/components/TelegramNotifyPanel";

/* ────────────────────────────────────────────────────────────── */
/*  Types & constants                                            */
/* ────────────────────────────────────────────────────────────── */

/* ────────────────────────────────────────────────────────────── */
/*  Bank CATALOG — used ONLY by the "select which banks to LINK"  */
/*  step of the wizard. This is the product catalog of banks the  */
/*  platform supports for outgoing user-server registrations, NOT */
/*  a list of "what's linked to a key". For "what's linked to a   */
/*  key" use useLinkedBanks() — never combine the two lists.       */
/* ────────────────────────────────────────────────────────────── */

const BANK_CATALOG: BankType[] = ["BAKONG", "ABA", "ACLEDA", "WING"];

type Step =
  | "select_banks"
  | "select_duration"
  | "pay"
  | "upload_qrs"
  | "merchant_name"
  | "done";

const STEP_LABELS: { id: Step; label: string }[] = [
  { id: "select_banks",    label: "Banks" },
  { id: "select_duration", label: "Plan" },
  { id: "pay",             label: "Pay" },
  { id: "upload_qrs",      label: "Bank QRs" },
  { id: "merchant_name",   label: "Name" },
  { id: "done",            label: "Done" },
];

interface PricedPlan { id: string; label: string; days: number; price: number; discount: string }
interface PricingTable { bankCount: number; base: number; plans: PricedPlan[] }
interface ActivationResult { apiKey: string; planType: string; days: number; expiresAt: string }
interface PaymentDraft {
  transactionId: string;
  qrPayload: string;
  qrImage: string;            // base64 PNG
  md5: string;
  amount: number;
  days: number;
  method: string;
}
interface DecodedQr {
  bank: BankType; merchantName: string; merchantAccount: string;
  merchantNetwork: string; merchantIssuer: string; phone?: string;
}

/** Per-bank linking state. */
interface BankLink {
  /** Has this bank been saved to the server. */
  saved: boolean;
  /** Toggle: upload an image vs. paste the QR string vs. (Bakong only) text format. */
  inputMode: "upload" | "paste" | "bakong";
  qrFile: File | null;
  qrPreview: string | null;
  qrString: string;
  merchantId: string;            // ABA: PayWay ID, Bakong: handle@bank
  phone: string;                 // Bakong: phone number (+ shown when ABA needs it)
  /** Decoded info shown back to the user after they paste/upload. */
  decoded: DecodedQr | null;
  /** Server-side result of save. */
  serverMerchantName?: string;
  serverAccount?: string;
}
const blankLink = (initial: Partial<BankLink> = {}): BankLink => ({
  saved: false,
  inputMode: "upload",
  qrFile: null, qrPreview: null, qrString: "",
  merchantId: "", phone: "",
  decoded: null,
  ...initial,
});

const BANK_FLOW: Record<BankType, {
  title: string;
  subtitle: string;
  hint: string;
  needsId: boolean;
  needsPhone: boolean;
  /** True if this bank uses the QR-upload route (vs. Bakong's text format). */
  needsQr: boolean;
}> = {
  BAKONG: {
    title: "Bakong",
    subtitle: "merchant_id@bank + phone",
    hint: "Format: yourname@aclb 0977416126",
    needsId: true, needsPhone: true, needsQr: false,
  },
  ABA: {
    title: "ABA Bank",
    subtitle: "Upload QR + Merchant ID / PayWay link",
    hint: "Paste your ABA QR string, then add the PayWay merchant link.",
    needsId: true, needsPhone: false, needsQr: true,
  },
  ACLEDA: {
    title: "ACLEDA",
    subtitle: "Upload QR — auto-extract account",
    hint: "Open ACLEDA Mobile → Receive Money → Save QR → upload here.",
    needsId: false, needsPhone: false, needsQr: true,
  },
  WING: {
    title: "Wing",
    subtitle: "Upload QR — auto-extract account",
    hint: "Open Wing → Receive Money → Save QR → upload here.",
    needsId: false, needsPhone: false, needsQr: true,
  },
};

/* ────────────────────────────────────────────────────────────── */
/*  Main component                                               */
/* ────────────────────────────────────────────────────────────── */

export default function BuyKey() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>("select_banks");
  /** When the user has an active key, we show a "you already have one" gate
   *  before the wizard. This flag lets them dismiss the gate and proceed to
   *  mint another anyway. */
  const [forceNew, setForceNew] = useState(false);

  // step 1: bank selection
  const [banks, setBanks] = useState<BankType[]>([]);

  // step 2: plan
  const [planId, setPlanId] = useState<string | null>(null);

  // step 4: link merchants
  const [links, setLinks] = useState<Record<BankType, BankLink>>({
    BAKONG: blankLink({ inputMode: "bakong" }),
    ABA: blankLink({ inputMode: "paste" }),
    ACLEDA: blankLink(),
    WING: blankLink(),
  });

  // step 5: merchant name (auto-filled from decoded QR)
  const [merchantName, setMerchantName] = useState("");
  const [suggestedName, setSuggestedName] = useState("");

  // step 6: result
  const [activation, setActivation] = useState<ActivationResult | null>(null);

  // step 5: payment draft (pay-step screen)
  const [payment, setPayment] = useState<PaymentDraft | null>(null);
  const [paymentMethod, setPaymentMethod] = useState<BankType>("BAKONG");

  const stepIndex = STEP_LABELS.findIndex((s) => s.id === step);

  /* ──────────── pricing ──────────── */

  const pricingQ = useQuery<PricingTable>({
    queryKey: ["pricing", banks.length],
    queryFn: () => apiGet(`/api/v1/buy-key/pricing?bankCount=${Math.max(1, banks.length)}`),
    enabled: banks.length > 0,
  });
  const selectedPlan = pricingQ.data?.plans.find((p) => p.id === planId);

  /* ──────────── decode & save per-bank ──────────── */

  function updateLink(b: BankType, patch: Partial<BankLink>) {
    setLinks((prev) => ({ ...prev, [b]: { ...prev[b], ...patch } }));
  }

  function onFile(b: BankType, e: ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    if (!f) return;
    if (!f.type.startsWith("image/")) { toast.error("PNG or JPG only"); return; }
    const reader = new FileReader();
    reader.onload = () => {
      updateLink(b, { qrFile: f, qrPreview: reader.result as string });
      // Auto-decode for preview
      decodeNow(b, { file: f });
    };
    reader.readAsDataURL(f);
  }

  async function decodeNow(b: BankType, src: { file?: File; qrString?: string }) {
    try {
      const fd = new FormData();
      if (src.file) fd.append("qr", src.file);
      if (src.qrString) fd.append("qrString", src.qrString);
      const r = await api.post("/api/v1/merchants/decode", fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      const decoded: DecodedQr = r.data.data;
      updateLink(b, { decoded });

      // Bank-mismatch warning — toast immediately so the user knows BEFORE
      // they click Save. The Save button is also disabled while mismatched.
      if (b !== "BAKONG" && decoded.bank && decoded.bank !== b) {
        toast.error(
          `This is a ${decoded.bank} QR — please upload a ${b} QR for the ${b} slot`,
          { id: `bank-mismatch-${b}` },
        );
      }

      // Auto-suggest merchant name only if the bank matches.
      if (decoded.merchantName && !suggestedName &&
          (b === "BAKONG" || decoded.bank === b)) {
        setSuggestedName(decoded.merchantName);
      }
    } catch (e) {
      // silent — server returns nice error if save is attempted
    }
  }

  function normalizeAbaId(raw: string) {
    const v = raw.trim();
    if (v.includes("payway.com.kh")) {
      return v.replace(/\/$/, "").split("/").pop() ?? v;
    }
    return v;
  }

  function parseBakongInline(s: BankLink): { merchantId: string; phone: string } | null {
    const text = (s.qrString || `${s.merchantId} ${s.phone}`).trim();
    const parts = text.split(/\s+/);
    if (parts.length < 2) return null;
    const [mid, ph] = parts;
    if (!mid.includes("@")) return null;
    return { merchantId: mid, phone: ph };
  }

  const saveLink = useMutation({
    mutationFn: async (b: BankType) => {
      const flow = BANK_FLOW[b];
      const s = links[b];
      const fd = new FormData();
      fd.append("bankType", b);

      if (b === "BAKONG") {
        const parsed = parseBakongInline(s);
        if (!parsed)
          throw new Error("Bakong format: merchant_id@bank phone");
        fd.append("merchantId", parsed.merchantId);
        fd.append("phone", parsed.phone);
      } else {
        if (s.inputMode === "upload" && s.qrFile) fd.append("qr", s.qrFile);
        else if (s.inputMode === "paste" && s.qrString.trim())
          fd.append("qrString", s.qrString.trim());
        else throw new Error(`${flow.title}: upload a QR or paste the QR string`);

        if (flow.needsId) {
          if (!s.merchantId.trim())
            throw new Error(`${flow.title}: ${b === "ABA" ? "PayWay merchant ID required" : "Merchant ID required"}`);
          fd.append("merchantId",
              b === "ABA" ? normalizeAbaId(s.merchantId) : s.merchantId.trim());
        }
        if (flow.needsPhone && s.phone.trim()) fd.append("phone", s.phone.trim());
      }

      if (merchantName) fd.append("merchantName", merchantName);

      const r = await api.post("/api/v1/merchants/upload", fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      return { bank: b, data: r.data.data };
    },
    onSuccess: ({ bank, data }) => {
      updateLink(bank, {
        saved: true,
        serverMerchantName: data.merchantName,
        serverAccount: data.merchantId,
      });
      // Auto-suggest the name from the first saved bank
      if (!suggestedName && data.merchantName) setSuggestedName(data.merchantName);
      toast.success(`${bank} saved`);
    },
    onError: (e: ApiError) => toast.error(e.message ?? "Save failed"),
  });

  const allBanksSaved = banks.every((b) => links[b].saved);

  // Detect existing keys so we can short-circuit the wizard when the user
  // already has an active one, and call /buy-additional instead of /refresh
  // (refresh would soft-revoke the previous key).
  interface ExistingKeyRow {
    id: string;
    key: string;
    label?: string | null;
    revoked: boolean;
    expired: boolean;
    expiresAt?: string;
    banks?: string[];
  }
  const existingKeysQ = useQuery<ExistingKeyRow[]>({
    queryKey: ["upstream-key", "list", "wizard"],
    queryFn: () => apiGet("/api/v1/me/upstream-key/list?reveal=true"),
    refetchOnWindowFocus: false,
  });
  const usableExisting = (existingKeysQ.data ?? [])
      .filter((k) => !k.revoked && !k.expired);
  const hasExistingKey = usableExisting.length > 0;

  /* ──────────── activation ──────────── */

  /**
   * Build the platform payment KHQR — the user scans this with their
   * own bank app to pay us, before the API key is minted.
   */
  const startPayment = useMutation({
    mutationFn: async () => {
      const r = await apiPost<PaymentDraft>("/api/v1/buy-key/payment-qr", {
        method: paymentMethod,
        amount: selectedPlan?.price ?? 1.5,
        days: selectedPlan?.days ?? 30,
      });
      if (!r?.qrPayload || !r.qrImage) {
        throw new Error("Payment QR not generated");
      }
      return r;
    },
    onSuccess: (r) => { setPayment(r); },
    onError: (e: ApiError) => toast.error(e.message ?? "Could not start payment"),
  });

  /**
   * The wizard's final step. We call ONLY the upstream-key bridge —
   * the apicheckpayment bot is the only authority on API keys now.
   *
   * If the user already has at least one key, we call /buy-additional so
   * their previous key is preserved. Otherwise the standard /refresh path.
   */
  const activate = useMutation({
    mutationFn: async () => {
      const endpoint = hasExistingKey
        ? "/api/v1/me/upstream-key/buy-additional"
        : "/api/v1/me/upstream-key/refresh";
      const body: Record<string, unknown> = {
        planId: selectedPlan?.id ?? "1month",
        merchantName: merchantName || "Default",
        // Tell the server EXACTLY which banks the user uploaded for this
        // key. Without this whitelist, Spring would fall back to "every
        // active merchant on this user" and silently include leftovers
        // from earlier wizard runs (the bug behind the "I uploaded ABA
        // but ended up with ABA + Bakong + ACLEDA" report).
        banks: banks.map((b) => b.toLowerCase()),
        // Buyer-paid context — gets forwarded all the way to the upstream
        // /api/external/issue_key call so the admin Telegram DM shows the
        // real numbers (Amount: $1.50 USD / Method: KHQR_ABA / MD5: ...)
        // instead of "Amount: $0.00 USD / Method: EXTERNAL".
        amountPaid:    payment?.amount ?? selectedPlan?.price ?? 0,
        paymentMd5:    payment?.md5 ?? "",
        paymentMethod: paymentMethod ? `khqr_${paymentMethod.toLowerCase()}` : "",
      };
      if (hasExistingKey) {
        // Auto-label additional keys so they're identifiable in the list.
        body.label = `Key ${(existingKeysQ.data?.length ?? 0) + 1}`;
      }
      const r = await apiPost<{ key: string; expiresAt: string; merchantName?: string }>(
          endpoint, body);
      if (!r?.key || !/^sk_[a-f0-9]{32}$/.test(r.key)) {
        throw new Error(`Bot returned bad key format. Got: ${r?.key ?? "(empty)"}`);
      }
      return r;
    },
    onSuccess: (r) => {
      setActivation({
        apiKey: r.key,
        planType: (selectedPlan?.days ?? 30) >= 365 ? "PRO"
                : (selectedPlan?.days ?? 30) >= 90  ? "BASIC"
                                                    : "FREE",
        days: selectedPlan?.days ?? 30,
        expiresAt: r.expiresAt,
      });
      qc.invalidateQueries({ queryKey: ["upstream-key"] });
      qc.invalidateQueries({ queryKey: ["sub"] });
      qc.invalidateQueries({ queryKey: ["merchants"] });
      setStep("done");
    },
    onError: (e: ApiError) => {
      // KEY_LIMIT is the only special case — we keep the user on the
      // pay step with a clear message instead of just toasting.
      if (e?.code === "KEY_LIMIT") {
        toast.error(
          "Payment received, but your current active key blocks new ones. Remove it from API Keys first.",
          { duration: 6500 },
        );
      } else {
        toast.error(e.message ?? "Could not mint key");
      }
    },
  });

  /* ──────────── navigation ──────────── */

  function next() {
    switch (step) {
      case "select_banks":
        if (banks.length === 0) { toast.error("Pick at least one bank"); return; }
        setStep("select_duration"); break;
      case "select_duration":
        if (!selectedPlan) { toast.error("Pick a plan"); return; }
        // Move to pay step where user chooses a method + clicks Generate.
        setPayment(null);
        setStep("pay"); break;
      case "pay":
        // After payment confirmed, move to bank QR linking.
        setStep("upload_qrs"); break;
      case "upload_qrs":
        if (!allBanksSaved) {
          toast.error("Save the bank info for every selected bank");
          return;
        }
        if (suggestedName && !merchantName) setMerchantName(suggestedName);
        setStep("merchant_name"); break;
      case "merchant_name":
        if (merchantName.trim().length < 2) { toast.error("Name too short"); return; }
        // Mint the actual sk_ key now that everything is in place.
        activate.mutate(); break;
    }
  }

  function back() {
    const idx = STEP_LABELS.findIndex((s) => s.id === step);
    if (idx > 0) setStep(STEP_LABELS[idx - 1].id);
  }

  /** When to show the gate vs the wizard body. */
  const showGate = hasExistingKey && !forceNew;

  return (
    <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight flex items-center gap-2">
            <Sparkles size={20} className="text-brand-600"/> Buy API Key
            <span className="badge-success">FREE</span>
          </h1>
          <p className="text-ink-500 mt-1">
            Pick banks → choose plan → pay → link bank QRs → name → key.
            <strong className="text-accent-700"> 100% free right now.</strong>
          </p>
        </div>

        {/* If the user already has an active key, intercept the wizard and
            show their key instead of letting them mint a duplicate. */}
        {showGate && (
          <ExistingKeyGate
            existing={usableExisting[0]}
            extraCount={Math.max(0, usableExisting.length - 1)}
            onUseExisting={() => navigate("/app/keys")}
            onTestExisting={() => navigate("/app/test")}
            onMintAnyway={() => {
              // Let the user run the FULL wizard (banks → plan → QRs →
              // name → pay → mint). The /buy-additional call will reject
              // with KEY_LIMIT until they remove the existing key, but
              // they can at least preview the price + scan QR flow.
              setForceNew(true);
              toast(
                "Heads up: you'll need to remove your current key before the new one can mint.",
                { duration: 4500, icon: "ℹ️" },
              );
            }}
          />
        )}

        {/* Banner shown after the user dismissed the gate. */}
        {!showGate && hasExistingKey && forceNew && (
          <div className="rounded-2xl bg-amber-50 ring-1 ring-amber-200 px-4 py-3 flex items-start gap-3">
            <AlertTriangle size={16} className="text-amber-600 mt-0.5 shrink-0"/>
            <div className="text-[13px] flex-1">
              <div className="font-semibold text-amber-900">
                You still have an active key
              </div>
              <div className="text-amber-800/90">
                Walk through the wizard freely — but you'll need to{" "}
                <Link to="/app/keys" className="underline font-semibold">
                  remove the current key
                </Link>{" "}
                before this new one can be minted.
              </div>
            </div>
            <button
              onClick={() => setForceNew(false)}
              className="text-amber-700 hover:text-amber-900 transition shrink-0"
              aria-label="Show existing key"
            >
              <XIcon size={14}/>
            </button>
          </div>
        )}

      {!showGate && <Stepper step={step} stepIndex={stepIndex}/>}

      {!showGate && (
        <AnimatePresence mode="wait">
          <motion.div key={step}
                      initial={{ opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={{ opacity: 0, y: -6 }}
                      transition={{ duration: 0.2 }}>
            {step === "select_banks" && (
              <SelectBanks banks={banks} setBanks={setBanks}/>
            )}
            {step === "select_duration" && (
              <SelectDuration banks={banks} pricing={pricingQ.data}
                              loading={pricingQ.isFetching}
                              planId={planId} setPlanId={setPlanId}/>
            )}
            {step === "upload_qrs" && (
              <UploadQRs banks={banks}
                         links={links}
                         updateLink={updateLink}
                         onFile={onFile}
                         onPaste={(b, qrString) => {
                           updateLink(b, { qrString });
                           if (qrString.startsWith("0002")) decodeNow(b, { qrString });
                         }}
                         saveLink={(b) => saveLink.mutate(b)}
                         saving={saveLink.isPending}/>
            )}
            {step === "merchant_name" && (
              <MerchantName name={merchantName}
                            setName={setMerchantName}
                            suggested={suggestedName}/>
            )}
            {step === "pay" && (
              <PayStep
                payment={payment}
                plan={selectedPlan}
                merchantName={merchantName}
                method={paymentMethod}
                onMethodChange={(m) => { setPaymentMethod(m); setPayment(null); }}
                generating={startPayment.isPending}
                activating={activate.isPending}
                onGenerate={() => startPayment.mutate()}
                onPaid={() => setStep("upload_qrs")}
                onRegenerate={() => startPayment.mutate()}
              />
            )}
            {step === "done" && <Done activation={activation}/>}
          </motion.div>
        </AnimatePresence>
      )}

      {!showGate && step !== "done" && step !== "pay" && (
        <div className="flex items-center justify-between">
          <button onClick={back} disabled={stepIndex === 0} className="btn-secondary">
            <ArrowLeft size={14}/> Back
          </button>
          <button onClick={next} className="btn-primary"
                  disabled={activate.isPending || startPayment.isPending}>
            {activate.isPending
              ? <><Loader2 size={14} className="animate-spin"/> Minting key…</>
              : step === "select_duration"
                ? <>Continue <ArrowRight size={14}/></>
                : step === "merchant_name"
                  ? <>Generate API Key <KeyRound size={14}/></>
                  : <>Continue <ArrowRight size={14}/></>}
          </button>
        </div>
      )}
      {!showGate && step === "pay" && (
        <div className="flex items-center justify-between">
          <button onClick={back} className="btn-secondary">
            <ArrowLeft size={14}/> Back
          </button>
          <span className="text-xs text-ink-400">
            We auto-detect payments — no need to click anything once you scan.
          </span>
        </div>
      )}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────── */
/*  Subcomponents                                                */
/* ────────────────────────────────────────────────────────────── */

function Stepper({ step, stepIndex }: { step: Step; stepIndex: number }) {
  return (
    <div className="card card-pad">
      <div className="flex items-center justify-between gap-2">
        {STEP_LABELS.map((l, i) => {
          const done = i < stepIndex;
          const active = i === stepIndex;
          return (
            <div key={l.id} className="flex-1 flex items-center">
              <div className="flex flex-col items-center gap-1.5 min-w-0">
                <div className={`grid h-8 w-8 place-items-center rounded-full text-xs font-bold transition-all
                                  ${done ? "bg-accent-500 text-white"
                                    : active ? "bg-brand-600 text-white scale-110 shadow-glow"
                                             : "bg-ink-100 text-ink-500"}`}>
                  {done ? <Check size={14}/> : i + 1}
                </div>
                <span className={`text-[11px] font-medium truncate ${active ? "text-ink-900" : "text-ink-500"}`}>
                  {l.label}
                </span>
              </div>
              {i < STEP_LABELS.length - 1 && (
                <div className={`flex-1 h-0.5 mx-1 transition-colors
                                ${i < stepIndex ? "bg-accent-500" : "bg-ink-200"}`}/>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function SelectBanks({ banks, setBanks }: {
  banks: BankType[]; setBanks: (b: BankType[]) => void;
}) {
  function toggle(b: BankType) {
    setBanks(banks.includes(b) ? banks.filter((x) => x !== b) : [...banks, b]);
  }
  return (
    <div className="card card-pad">
      <div className="mb-4">
        <div className="text-lg font-bold">1. Pick your banks</div>
        <p className="text-sm text-ink-500">
          Tap any combination — even all four. Pricing scales accordingly.
        </p>
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {BANK_CATALOG.map((b, i) => {
          const sel = banks.includes(b);
          return (
            <button key={b} onClick={() => toggle(b)}
                    className={`relative rounded-2xl border-2 px-3 py-4 text-left transition
                              ${sel ? "border-brand-500 bg-brand-50 ring-2 ring-brand-500/20 shadow-soft"
                                    : "border-ink-200 hover:border-ink-300 hover:bg-ink-50"}`}>
              <div className="flex items-center justify-between">
                <span className={`grid h-7 w-7 place-items-center rounded-full text-[11px] font-bold
                                  ${sel ? "bg-brand-600 text-white" : "bg-ink-200 text-ink-700"}`}>
                  {sel ? <Check size={14}/> : i + 1}
                </span>
                <BankBadge bank={b} className="!px-1.5 !py-0.5 !text-[10px]"/>
              </div>
              <div className="mt-2.5 text-sm font-bold">{BANK_FLOW[b].title}</div>
              <div className="mt-1 text-[10px] text-ink-500 leading-tight">
                {BANK_FLOW[b].subtitle}
              </div>
            </button>
          );
        })}
      </div>
      <p className="help mt-3">{banks.length} bank{banks.length === 1 ? "" : "s"} selected.</p>
    </div>
  );
}

function SelectDuration({ banks, pricing, loading, planId, setPlanId }: {
  banks: BankType[]; pricing?: PricingTable; loading: boolean;
  planId: string | null; setPlanId: (id: string) => void;
}) {
  return (
    <div className="card card-pad">
      <div className="mb-4">
        <div className="text-lg font-bold flex items-center gap-2">
          2. Pick a plan
          <span className="badge-success">FREE</span>
        </div>
        <p className="text-sm text-ink-500">
          All plans are <strong>free</strong> right now — pick how long you want your key to stay active.
          You picked <strong>{banks.length} bank{banks.length > 1 ? "s" : ""}</strong>.
        </p>
      </div>
      {loading ? (
        <div className="text-center py-12 text-ink-400">
          <Loader2 className="inline animate-spin" size={20}/>
        </div>
      ) : (
        <div className="space-y-2">
          {pricing?.plans.map((p) => {
            const sel = planId === p.id;
            const isFree = (p.price ?? 0) === 0;
            return (
              <button key={p.id} onClick={() => setPlanId(p.id)}
                      className={`w-full text-left rounded-2xl border-2 p-4 flex items-center justify-between transition
                                  ${sel ? "border-brand-500 bg-brand-50 ring-2 ring-brand-500/20"
                                        : "border-ink-200 hover:border-ink-300 hover:bg-ink-50"}`}>
                <div>
                  <div className="font-bold text-ink-900">{p.label}</div>
                  <div className="text-xs text-ink-500 mt-0.5">{p.discount}</div>
                </div>
                <div className="text-right">
                  {isFree ? (
                    <div className="text-2xl font-extrabold text-accent-700">FREE</div>
                  ) : (
                    <div className="text-2xl font-extrabold">${p.price.toFixed(2)}</div>
                  )}
                  <div className="text-[10px] text-ink-400">{p.days} days</div>
                </div>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────── */
/*  PayStep — state-machine pay screen matching the bot tester    */
/* ────────────────────────────────────────────────────────────── */

/** Public payment-state union (exported for any consumer). */
export type PaymentState =
  | "idle"        // A — no QR, ready to generate
  | "generating"  // B — POSTing /payment-qr
  | "waiting"    // C — QR live, polling
  | "paid"        // D — payment received
  | "expired"     // E.1 — TTL hit zero
  | "error";      // E.2 — server error

interface MethodBrand {
  ring: string; tint: string; text: string;
  btn: string; btnHover: string;
  dot: string;
  glow: string;
}
interface Method {
  id: BankType;
  slot: "bakong" | "aba" | "acleda" | "wing";
  label: string;
  brand: MethodBrand;
}

const PAY_METHODS: Method[] = [
  {
    id: "BAKONG", slot: "bakong", label: "Bakong KHQR",
    brand: {
      ring: "border-[#e21a1a] ring-[#e21a1a]/15",
      tint: "bg-[#e21a1a]/[0.04]",
      text: "text-[#e21a1a]",
      btn:  "bg-[#e21a1a]",  btnHover: "hover:bg-[#c01616]",
      dot:  "bg-[#e21a1a]",
      glow: "#e21a1a",
    },
  },
  {
    id: "ACLEDA", slot: "acleda", label: "ACLEDA Bank",
    brand: {
      ring: "border-emerald-600 ring-emerald-500/15",
      tint: "bg-emerald-50",
      text: "text-emerald-700",
      btn:  "bg-emerald-600", btnHover: "hover:bg-emerald-700",
      dot:  "bg-emerald-500",
      glow: "#10b981",
    },
  },
  {
    id: "ABA", slot: "aba", label: "ABA Bank",
    brand: {
      ring: "border-rose-600 ring-rose-500/15",
      tint: "bg-rose-50",
      text: "text-rose-700",
      btn:  "bg-rose-600", btnHover: "hover:bg-rose-700",
      dot:  "bg-rose-500",
      glow: "#e11d48",
    },
  },
  {
    id: "WING", slot: "wing", label: "Wing Bank",
    brand: {
      ring: "border-sky-600 ring-sky-500/15",
      tint: "bg-sky-50",
      text: "text-sky-700",
      btn:  "bg-sky-600", btnHover: "hover:bg-sky-700",
      dot:  "bg-sky-500",
      glow: "#0ea5e9",
    },
  },
];

function PayStep({
  payment, plan, merchantName, method, onMethodChange,
  generating, activating, onGenerate, onPaid, onRegenerate,
}: {
  payment: PaymentDraft | null;
  plan?: PricedPlan;
  merchantName: string;
  method: BankType;
  onMethodChange: (b: BankType) => void;
  generating: boolean;
  activating: boolean;
  onGenerate: () => void;
  onPaid: () => void;
  onRegenerate: () => void;
}) {
  const [paid, setPaid] = useState(false);
  const [secsLeft, setSecsLeft] = useState(15 * 60);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [pendingSwitch, setPendingSwitch] = useState<BankType | null>(null);
  const onPaidRef = useRef(onPaid);
  onPaidRef.current = onPaid;
  const abortRef = useRef<AbortController | null>(null);
  /** Dedupe guard for the onPaid callback — fires exactly once per QR. */
  const paidFiredRef = useRef(false);

  /* Live platform-payment banks. Hides any tile the platform isn't
     actually registered for upstream — no more "Bank aba not registered"
     404s after the user clicks Generate. */
  const platformBanksQ = useQuery<{ banks: string[] }>({
    queryKey: ["buy-key", "payment-methods"],
    queryFn: () => apiGet("/api/v1/buy-key/payment-methods"),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
  });
  const allowedSlugs = (platformBanksQ.data?.banks ?? []).map((s) => s.toLowerCase());
  const visibleMethods = platformBanksQ.isLoading || allowedSlugs.length === 0
      ? PAY_METHODS                                        // no live data yet — show catalog
      : PAY_METHODS.filter((m) => allowedSlugs.includes(m.slot));
  const platformUnavailable = !platformBanksQ.isLoading
      && (platformBanksQ.data?.banks?.length ?? 0) === 0;

  // If the currently selected method isn't actually available, snap to the
  // first one that is so the user never clicks Generate against a 404.
  useEffect(() => {
    if (visibleMethods.length === 0) return;
    if (!visibleMethods.some((m) => m.id === method)) {
      onMethodChange(visibleMethods[0].id);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleMethods.map((m) => m.id).join(",")]);

  const expired = !!payment && !paid && secsLeft === 0;
  const state: PaymentState = errorMsg
    ? "error"
    : paid
      ? "paid"
      : expired
        ? "expired"
        : generating
          ? "generating"
          : payment
            ? "waiting"
            : "idle";

  const sel = PAY_METHODS.find((m) => m.id === method) ?? PAY_METHODS[0];

  useEffect(() => {
    setPaid(false);
    setErrorMsg(null);
    setSecsLeft(15 * 60);
    paidFiredRef.current = false;
  }, [payment?.md5]);

  useEffect(() => {
    if (!payment || paid) return;
    const id = window.setInterval(() => {
      setSecsLeft((s) => Math.max(0, s - 1));
    }, 1000);
    return () => window.clearInterval(id);
  }, [payment, paid]);

  useEffect(() => {
    if (!payment || paid) return;
    const ctrl = new AbortController();
    abortRef.current?.abort();
    abortRef.current = ctrl;

    let stopped = false;
    const tick = async () => {
      if (stopped || ctrl.signal.aborted) return;
      try {
        const r = await apiGet<{ paid: boolean; md5: string }>(
          `/api/v1/buy-key/poll-payment?md5=${encodeURIComponent(payment.md5)}`
          + `&qrPayload=${encodeURIComponent(payment.qrPayload)}`,
        );
        if (stopped || ctrl.signal.aborted) return;
        if (r.paid) {
          setPaid(true);
          // Fire the parent's onPaid exactly once per QR. We deliberately
          // DON'T gate this on ctrl.signal.aborted: the very act of setting
          // `paid=true` re-runs this effect's cleanup which aborts ctrl,
          // so checking it later would silently swallow the callback (the
          // exact bug that left the user stuck on the PAID screen forever).
          if (!paidFiredRef.current) {
            paidFiredRef.current = true;
            window.setTimeout(() => onPaidRef.current(), 1200);
          }
        }
      } catch { /* keep polling */ }
    };
    tick();
    const id = window.setInterval(tick, 1500);
    return () => { stopped = true; window.clearInterval(id); ctrl.abort(); };
  }, [payment, paid]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const mins = Math.floor(secsLeft / 60).toString().padStart(2, "0");
  const ss = (secsLeft % 60).toString().padStart(2, "0");

  function pickBank(b: BankType) {
    if (b === method) return;
    if (state === "waiting" || state === "generating") {
      setPendingSwitch(b);
      return;
    }
    onMethodChange(b);
  }
  function confirmSwitch() {
    if (!pendingSwitch) return;
    abortRef.current?.abort();
    onMethodChange(pendingSwitch);
    setPendingSwitch(null);
  }
  function cancelSwitch() { setPendingSwitch(null); }

  // Primary button states
  const buttonContent: { label: React.ReactNode; cls: string; disabled: boolean; onClick?: () => void; glow?: boolean } =
    state === "generating"
      ? {
          label: <><Loader2 size={15} className="animate-spin"/> Generating QR…</>,
          cls: `${sel.brand.btn} text-white opacity-75`,
          disabled: true,
        }
      : state === "waiting"
        ? {
            label: (
              <>
                <span className="h-1.5 w-1.5 rounded-full bg-white/90 animate-pulse"/>
                Waiting for payment
                <span className="ml-1.5 inline-flex items-center rounded-md bg-white/10 ring-1 ring-white/15 px-2 py-0.5 font-mono text-[12px] tabular-nums">
                  {mins}:{ss}
                </span>
              </>
            ),
            cls: "bg-ink-900 text-white cursor-not-allowed",
            disabled: true,
          }
        : state === "paid"
          ? {
              label: <><Check size={15}/> Paid · ${Number(payment?.amount ?? 0).toFixed(2)}</>,
              cls: "bg-emerald-600 text-white cursor-default",
              disabled: true,
            }
          : state === "expired"
            ? {
                label: <><RefreshCw size={15}/> Generate again</>,
                cls: "bg-rose-600 hover:bg-rose-700 text-white",
                disabled: false,
                onClick: onRegenerate,
                glow: true,
              }
            : state === "error"
              ? {
                  label: <><RefreshCw size={15}/> Try again</>,
                  cls: "bg-rose-600 hover:bg-rose-700 text-white",
                  disabled: false,
                  onClick: () => { setErrorMsg(null); onGenerate(); },
                }
              : {
                  label: <><QrCodeIcon size={15}/> Generate Pay QR</>,
                  cls: `${sel.brand.btn} ${sel.brand.btnHover} text-white`,
                  disabled: false,
                  onClick: onGenerate,
                  glow: true,
                };

  return (
    <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-5 items-stretch">
      {/* ───── LEFT — Form ───── */}
      <div className="rounded-3xl bg-white ring-1 ring-ink-200/70 shadow-[0_2px_8px_-2px_rgba(15,23,42,0.04),0_24px_48px_-16px_rgba(15,23,42,0.10)] p-7 lg:p-8">
        {/* Plan summary header */}
        <div className="flex items-baseline justify-between pb-5 border-b border-ink-100">
          <div>
            <div className="text-[11px] uppercase tracking-[0.18em] font-semibold text-ink-400 font-mono">
              Plan
            </div>
            <div className="mt-1 text-[18px] font-bold tracking-tight text-ink-900 flex items-baseline gap-2">
              {plan?.label ?? "1 Month"}
              <span className="text-[12px] font-medium text-ink-500">· {plan?.days ?? 30} days</span>
            </div>
          </div>
          <div className="text-right">
            <div className="text-[10px] uppercase tracking-[0.16em] font-mono text-ink-400">
              Total
            </div>
            <div className="text-[24px] font-extrabold tracking-tight text-ink-900 tabular-nums">
              ${Number(plan?.price ?? 0).toFixed(2)}
              <span className="ml-1 text-[10px] font-mono text-ink-400 font-semibold">USD</span>
            </div>
          </div>
        </div>

        {/* Heading */}
        <div className="mt-6 flex items-baseline justify-between">
          <div className="text-[11px] uppercase tracking-[0.18em] font-bold text-ink-700 font-mono">
            Payment Method
          </div>
          <span className="text-[11px] text-ink-400">Choose one</span>
        </div>

        {/* Bank picker — clean tiles with logo. Lives ONLY for banks the
            platform is actually registered for; nothing greyed out. */}
        {platformUnavailable ? (
          <div className="mt-3 rounded-xl bg-rose-50 ring-1 ring-rose-200 px-3.5 py-3 text-xs text-rose-900">
            <div className="flex items-start gap-2">
              <AlertTriangle size={14} className="text-rose-600 mt-0.5 shrink-0"/>
              <div>
                <div className="font-bold text-sm">Platform payment unavailable</div>
                <p className="mt-0.5 text-rose-800/85 leading-snug">
                  The platform's customer key isn't registered with any bank
                  upstream right now. Try again in a minute, or contact support.
                </p>
              </div>
            </div>
          </div>
        ) : (
          <div role="radiogroup" aria-label="Payment method"
               className={`mt-3 grid gap-2 ${visibleMethods.length === 1 ? "grid-cols-1" : "grid-cols-2"}`}>
            {visibleMethods.map((m) => {
              const selected = method === m.id;
              return (
                <button
                  role="radio"
                  aria-checked={selected}
                  type="button"
                  key={m.id}
                  onClick={() => pickBank(m.id)}
                  className={`group relative rounded-xl border px-3.5 py-3 text-left transition-all outline-none focus:ring-2 focus:ring-ink-900/15 ${
                    selected
                      ? `border-transparent ring-2 ${m.brand.tint}`
                      : "border-ink-200 hover:border-ink-300 bg-white"
                  }`}
                  style={selected ? { boxShadow: `0 0 0 2px ${m.brand.glow}, 0 8px 20px -12px ${m.brand.glow}66` } : undefined}
                >
                  <div className="flex items-center gap-2.5">
                    <BankLogo bank={m.slot} size={22} square/>
                    <div className="min-w-0 flex-1">
                      <div className={`text-[13px] font-bold leading-tight tracking-tight ${selected ? m.brand.text : "text-ink-900"}`}>
                        {m.label}
                      </div>
                      <div className="mt-0.5 text-[10px] text-ink-400 font-mono uppercase tracking-wider">
                        KHQR
                      </div>
                    </div>
                    <div className={`grid h-4 w-4 place-items-center rounded-full transition ${
                      selected
                        ? `${m.brand.btn} text-white`
                        : "ring-1 ring-ink-200 bg-white"
                    }`}>
                      {selected && <Check size={10} strokeWidth={3}/>}
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        )}

        {pendingSwitch && (
          <div className="mt-3 rounded-xl bg-amber-50 ring-1 ring-amber-200 px-3.5 py-2.5 text-[12px] flex items-center gap-2 flex-wrap">
            <AlertTriangle size={13} className="text-amber-600 shrink-0"/>
            <span className="flex-1 text-amber-900">
              Replace current QR with{" "}
              <strong>{PAY_METHODS.find((m) => m.id === pendingSwitch)?.label}</strong>?
            </span>
            <button onClick={cancelSwitch}
                    className="rounded-md bg-white ring-1 ring-amber-200 px-2.5 py-1 text-[11px] font-semibold text-amber-900 hover:bg-amber-100">
              Cancel
            </button>
            <button onClick={confirmSwitch}
                    className="rounded-md bg-amber-600 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-amber-700">
              Replace
            </button>
          </div>
        )}

        {/* Amount */}
        <div className="mt-5">
          <div className="text-[11px] uppercase tracking-[0.18em] font-bold text-ink-700 font-mono flex items-center gap-1.5">
            Amount <span className="text-ink-300 font-normal normal-case tracking-normal">· locked</span>
          </div>
          <div className="mt-2 flex items-center rounded-xl ring-1 ring-ink-200 bg-ink-50/50 px-3.5 py-2.5">
            <span className="text-ink-400 mr-2 text-[15px] font-bold">$</span>
            <input
              readOnly disabled
              value={Number(plan?.price ?? 0).toFixed(2)}
              aria-label="Amount in USD"
              className="flex-1 bg-transparent text-[16px] font-bold tracking-tight text-ink-900 tabular-nums outline-none cursor-not-allowed"
            />
            <span className="text-[10px] font-mono text-ink-400 ml-2 uppercase tracking-wide">USD</span>
          </div>
        </div>

        {/* Primary button */}
        <button
          onClick={buttonContent.onClick}
          disabled={buttonContent.disabled}
          aria-live="polite"
          className={`group relative mt-5 w-full rounded-xl py-3.5 font-bold text-[14.5px] inline-flex items-center justify-center gap-2 transition disabled:cursor-not-allowed overflow-hidden ${buttonContent.cls}`}
          style={
            buttonContent.glow && state === "idle"
              ? { boxShadow: `0 12px 28px -12px ${sel.brand.glow}aa, 0 4px 10px -4px ${sel.brand.glow}66` }
              : buttonContent.glow && state === "expired"
                ? { boxShadow: `0 12px 28px -12px rgba(225,29,72,0.5)` }
                : undefined
          }
        >
          {!buttonContent.disabled && (
            <span
              aria-hidden
              className="pointer-events-none absolute inset-y-0 -left-1/3 w-1/3 bg-gradient-to-r from-transparent via-white/20 to-transparent translate-x-0 group-hover:translate-x-[400%] transition-transform duration-700 ease-out"
            />
          )}
          <span className="relative z-10 inline-flex items-center gap-2">
            {buttonContent.label}
          </span>
        </button>

        {(state === "waiting" || state === "generating") && (
          <button
            onClick={() => { abortRef.current?.abort(); onRegenerate(); }}
            className="mt-3 w-full text-[12px] font-semibold text-ink-500 hover:text-ink-900 transition inline-flex items-center justify-center gap-1.5"
          >
            <RefreshCw size={11}/> Regenerate with another bank
          </button>
        )}

        {state === "error" && errorMsg && (
          <p className="mt-2 text-[11px] text-rose-600">{errorMsg}</p>
        )}

        <div className="mt-6 pt-4 border-t border-ink-100 flex items-start gap-2">
          <span className="grid h-4 w-4 mt-0.5 place-items-center rounded-full bg-ink-900 text-white text-[9px] font-bold shrink-0">i</span>
          <p className="text-[11px] text-ink-500 leading-relaxed">
            KHQR works with <strong className="text-ink-700">any</strong> Cambodian banking app. Your key is minted automatically the second the payment lands.
          </p>
        </div>

        <div className="sr-only" aria-live="polite">
          {state === "waiting" && `${Math.floor(secsLeft / 60)} minutes remaining`}
          {state === "paid" && "Payment received"}
          {state === "expired" && "QR expired"}
        </div>
      </div>

      {/* ───── RIGHT — Receipt panel (clean & focused) ───── */}
      <div className="relative rounded-3xl bg-white ring-1 ring-ink-200/70 shadow-[0_2px_8px_-2px_rgba(15,23,42,0.04),0_24px_48px_-16px_rgba(15,23,42,0.10)] overflow-hidden">
        {/* Header strip */}
        <div className="px-7 lg:px-8 pt-7 pb-5 border-b border-ink-100 flex items-baseline justify-between">
          <div>
            <div className="text-[11px] uppercase tracking-[0.18em] font-semibold text-ink-400 font-mono">
              Step 3 of 6
            </div>
            <div className="mt-1 text-[18px] font-bold tracking-tight text-ink-900">
              Scan & pay
            </div>
            <div className="mt-0.5 text-[12px] text-ink-500">
              Open your banking app, scan the QR, confirm the amount.
            </div>
          </div>
          {paid ? (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 ring-1 ring-emerald-200 px-2.5 py-1 text-[10px] font-bold text-emerald-700 uppercase tracking-[0.16em]">
              <Check size={11}/> Paid
            </span>
          ) : payment ? (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-rose-50 ring-1 ring-rose-200 px-2.5 py-1 text-[10px] font-bold text-rose-700 uppercase tracking-[0.16em]">
              <span className="h-1.5 w-1.5 rounded-full bg-rose-500 animate-pulse"/>
              Live
            </span>
          ) : (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-ink-100 ring-1 ring-ink-200 px-2.5 py-1 text-[10px] font-bold text-ink-500 uppercase tracking-[0.16em]">
              Idle
            </span>
          )}
        </div>

        {/* Receipt stage */}
        <div className="relative px-6 lg:px-8 py-8">
          {/* Soft brand wash that swaps on bank pick */}
          <div
            aria-hidden
            className="pointer-events-none absolute inset-x-0 top-0 h-48 transition-colors duration-700"
            style={{
              background: `radial-gradient(ellipse 90% 100% at 50% 0%, ${sel.brand.glow}14, transparent 70%)`,
            }}
          />

          {/* Scan beam while generating */}
          {state === "generating" && (
            <div
              aria-hidden
              className="absolute left-1/2 -translate-x-1/2 top-8 h-[420px] w-[300px] overflow-hidden rounded-[28px] pointer-events-none"
            >
              <div
                className="absolute inset-x-0 h-12 buy-scan-beam"
                style={{
                  background: `linear-gradient(180deg, transparent, ${sel.brand.glow}55 50%, transparent)`,
                }}
              />
            </div>
          )}

          {/* Receipt — animated entrance on bank/QR change */}
          <div className="relative flex justify-center">
            <AnimatePresence mode="wait">
              <motion.div
                key={`${method}-${payment?.md5 ?? "empty"}`}
                initial={{ opacity: 0, y: 12, scale: 0.97 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: -8, scale: 0.98 }}
                transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
              >
                <KhqrReceiptCard
                  bank={sel.label}
                  merchantName={merchantName || "Byme Bank Gateway"}
                  amount={Number(payment?.amount ?? plan?.price ?? 0)}
                  currency="USD"
                  qrImage={payment?.qrImage}
                  paid={paid}
                  loading={generating}
                  footer="Scan with any KHQR-compatible app"
                />
              </motion.div>
            </AnimatePresence>
          </div>
        </div>

        {/* Footer status bar */}
        <div className="px-7 lg:px-8 py-3.5 border-t border-ink-100 bg-ink-50/40 flex items-center justify-between gap-3">
          <div className="text-[12px] flex items-center gap-2">
            <span
              className="inline-block h-2 w-2 rounded-full transition-colors duration-300"
              style={{
                background: paid
                  ? "#10b981"
                  : expired
                    ? "#f43f5e"
                    : payment
                      ? sel.brand.glow
                      : "#94a3b8",
                boxShadow: payment && !paid && !expired ? `0 0 8px ${sel.brand.glow}` : undefined,
              }}
            />
            <span className="text-ink-600 font-medium">
              {!payment ? (
                <>Waiting on you. Click <span className="font-bold text-ink-900">Generate Pay QR</span>.</>
              ) : expired ? (
                <span className="text-rose-600 font-semibold">QR expired — generate again</span>
              ) : paid ? (
                <span className="text-emerald-700 font-semibold">Payment confirmed · activating</span>
              ) : (
                <>
                  Listening for payment ·{" "}
                  <span className="font-mono tabular-nums text-ink-900 font-bold">{mins}:{ss}</span>
                </>
              )}
            </span>
          </div>
          {state === "waiting" && (
            <button
              onClick={() => { abortRef.current?.abort(); onRegenerate(); }}
              className="inline-flex items-center gap-1 rounded-md bg-white ring-1 ring-ink-200 px-2.5 py-1 text-[11px] font-semibold text-ink-700 hover:bg-ink-50 transition"
            >
              <RefreshCw size={11}/> New QR
            </button>
          )}
          {state === "paid" && (
            <span className="inline-flex items-center gap-1 text-[11px] text-emerald-700 font-semibold">
              {activating
                ? <><Loader2 size={11} className="animate-spin"/> Minting key…</>
                : <><Check size={11}/> Redirecting…</>}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

function UploadQRs({ banks, links, updateLink, onFile, onPaste, saveLink, saving }: {
  banks: BankType[];
  links: Record<BankType, BankLink>;
  updateLink: (b: BankType, p: Partial<BankLink>) => void;
  onFile: (b: BankType, e: ChangeEvent<HTMLInputElement>) => void;
  onPaste: (b: BankType, qrString: string) => void;
  saveLink: (b: BankType) => void;
  saving: boolean;
}) {
  return (
    <div className="card card-pad">
      <div className="mb-4">
        <div className="text-lg font-bold">4. Add your bank QR info</div>
        <p className="text-sm text-ink-500">
          We'll route customer payments straight to these accounts.
          Save each bank, then continue.
        </p>
      </div>
      <div className="space-y-4">
        {banks.map((b) => (
          <BankPanel key={b}
                     bank={b}
                     state={links[b]}
                     update={(p) => updateLink(b, p)}
                     onFile={(e) => onFile(b, e)}
                     onPaste={(s) => onPaste(b, s)}
                     onSave={() => saveLink(b)}
                     saving={saving}/>
        ))}
      </div>
    </div>
  );
}

function BankPanel({ bank, state, update, onFile, onPaste, onSave, saving }: {
  bank: BankType;
  state: BankLink;
  update: (p: Partial<BankLink>) => void;
  onFile: (e: ChangeEvent<HTMLInputElement>) => void;
  onPaste: (s: string) => void;
  onSave: () => void;
  saving: boolean;
}) {
  const flow = BANK_FLOW[bank];

  return (
    <div className={`rounded-2xl border p-4 transition
                     ${state.saved
                       ? "border-accent-300 bg-accent-50/40"
                       : "border-ink-100 bg-ink-50/40"}`}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <BankBadge bank={bank}/>
          <span className="text-sm font-bold text-ink-800">{flow.subtitle}</span>
        </div>
        {state.saved
          ? <span className="badge-success"><Check size={10}/> saved</span>
          : <span className="badge-muted">Not yet</span>}
      </div>

      {/* BAKONG: only the inline text format */}
      {bank === "BAKONG" ? (
        <BakongInline state={state} update={update}/>
      ) : (
        <>
          {/* upload / paste toggle */}
          <div className="flex items-center justify-between mb-2">
            <div className="text-xs font-semibold text-ink-700">QR code</div>
            <div className="inline-flex rounded-lg bg-ink-100 p-0.5 text-[11px]">
              {(["upload", "paste"] as const).map((mode) => (
                <button type="button" key={mode}
                        onClick={() => update({ inputMode: mode })}
                        className={`px-2.5 py-1 rounded-md font-semibold transition ${
                          state.inputMode === mode
                            ? "bg-white shadow-soft text-ink-900"
                            : "text-ink-500 hover:text-ink-800"
                        }`}>
                  {mode === "upload" ? "Upload image" : "Paste string"}
                </button>
              ))}
            </div>
          </div>

          {state.inputMode === "upload" ? (
            <UploadDrop state={state} onFile={onFile}/>
          ) : (
            <textarea
              className="input font-mono text-[11px] h-20 resize-none"
              value={state.qrString}
              onChange={(e) => onPaste(e.target.value)}
              placeholder="Paste your QR string starting with 0002…"
            />
          )}

          {/* Decoded preview — `bank` here is never BAKONG (handled above). */}
          {state.decoded && (() => {
            const mismatch = !!state.decoded.bank && state.decoded.bank !== bank;
            return (
              <div className={`mt-2 rounded-lg ring-1 p-2.5 text-xs ${
                mismatch
                  ? "bg-rose-50 ring-rose-200"
                  : "bg-white ring-ink-100"
              }`}>
                <div className={`font-semibold flex items-center gap-1.5 ${
                  mismatch ? "text-rose-800" : "text-ink-700"
                }`}>
                  {mismatch
                    ? <><XIcon size={12} className="text-rose-600"/> Wrong bank</>
                    : <><Check size={12} className="text-accent-600"/> Detected</>}
                </div>
                {mismatch && (
                  <p className="mt-1 text-[11px] text-rose-700 leading-snug">
                    This is a <strong className="font-mono">{state.decoded.bank}</strong> QR.
                    You're filling the <strong>{bank}</strong> slot — please upload a{" "}
                    <strong>{bank}</strong> QR or switch the slot.
                  </p>
                )}
                <div className="mt-1 grid grid-cols-2 gap-1 text-ink-500">
                  <div>name <span className="text-ink-900 font-mono">{state.decoded.merchantName || "—"}</span></div>
                  <div>account <span className="text-ink-900 font-mono">{state.decoded.merchantAccount || "—"}</span></div>
                  <div>issuer <span className="text-ink-900 font-mono">{state.decoded.merchantIssuer || "—"}</span></div>
                  <div>bank <span className={`font-mono font-bold ${
                    mismatch ? "text-rose-700" : "text-ink-900"
                  }`}>{state.decoded.bank}</span></div>
                </div>
              </div>
            );
          })()}

          {/* Bank-specific extras */}
          {flow.needsId && (
            <div className="mt-3">
              <label className="label flex items-center gap-1.5">
                <Hash size={12}/> {bank === "ABA" ? "ABA Merchant ID or PayWay link" : "Merchant ID"}
              </label>
              <input className="input font-mono text-xs"
                     value={state.merchantId}
                     onChange={(e) => update({ merchantId: e.target.value })}
                     placeholder={bank === "ABA"
                       ? "https://link.payway.com.kh/ABAPAYAE..."
                       : "merchantId"}/>
              <p className="help">{flow.hint}</p>
            </div>
          )}
        </>
      )}

      <div className="mt-4 flex items-center justify-end gap-2">
        <button
          onClick={onSave}
          disabled={
            saving ||
            state.saved ||
            // Block when the decoded bank doesn't match this slot.
            // (We're already inside the !== "BAKONG" branch.)
            (!!state.decoded &&
             !!state.decoded.bank &&
             state.decoded.bank !== bank)
          }
          className={`btn-primary ${state.saved ? "!bg-accent-600" : ""}`}
        >
          {saving
            ? <Loader2 size={14} className="animate-spin"/>
            : state.saved
              ? <><Check size={14}/> Saved</>
              : <><Save size={14}/> Save {bank}</>}
        </button>
      </div>
    </div>
  );
}

function BakongInline({ state, update }: {
  state: BankLink; update: (p: Partial<BankLink>) => void;
}) {
  const text = state.qrString || `${state.merchantId} ${state.phone}`.trim();
  return (
    <div>
      <div className="text-xs font-semibold text-ink-700 mb-2">
        Format <code className="font-mono text-[11px] bg-ink-100 rounded px-1.5 py-0.5">merchant_id@bank phone</code>
      </div>
      <input className="input font-mono"
             value={text}
             onChange={(e) => {
               const v = e.target.value;
               update({ qrString: v });
               // Also try to parse for the inline `merchantId` / `phone` fields
               const parts = v.trim().split(/\s+/);
               if (parts.length >= 2 && parts[0].includes("@")) {
                 update({ merchantId: parts[0], phone: parts[1] });
               }
             }}
             placeholder="hut_soksitchey1@aclb 0977416126"/>
      <p className="help flex items-center gap-1 mt-1">
        <Phone size={12}/> Bakong-registered phone number after the handle.
      </p>
    </div>
  );
}

function UploadDrop({ state, onFile }: {
  state: BankLink; onFile: (e: ChangeEvent<HTMLInputElement>) => void;
}) {
  return (
    <label className={`flex cursor-pointer items-center gap-3 rounded-xl border-2 border-dashed p-3 transition
                       ${state.qrPreview
                         ? "border-accent-500 bg-accent-50/40"
                         : "border-ink-300 bg-white hover:border-brand-500 hover:bg-brand-50/30"}`}>
      {state.qrPreview ? (
        <>
          <img src={state.qrPreview} alt="QR"
               className="h-20 w-20 rounded-lg object-contain bg-white ring-1 ring-ink-100"/>
          <div className="flex-1 min-w-0">
            <div className="font-semibold text-accent-700 text-sm flex items-center gap-1 truncate">
              <ImageIcon size={14}/> {state.qrFile?.name}
            </div>
            <div className="text-xs text-ink-500">
              {((state.qrFile?.size ?? 0) / 1024).toFixed(1)} KB
            </div>
          </div>
        </>
      ) : (
        <>
          <div className="h-20 w-20 grid place-items-center rounded-lg bg-ink-100 text-ink-400">
            <UploadCloud size={20}/>
          </div>
          <div>
            <div className="text-sm font-semibold">Click to upload QR image</div>
            <div className="text-xs text-ink-500">PNG / JPG, up to 5 MB</div>
          </div>
        </>
      )}
      <input type="file" accept="image/*" className="hidden" onChange={onFile}/>
    </label>
  );
}

function MerchantName({ name, setName, suggested }: {
  name: string; setName: (s: string) => void; suggested: string;
}) {
  return (
    <div className="card card-pad">
      <div className="mb-4">
        <div className="text-lg font-bold">5. Merchant name on QR card</div>
        <p className="text-sm text-ink-500">
          This is the name customers see when they scan to pay you. Up to 25 characters.
        </p>
      </div>
      <input className="input text-lg" maxLength={25}
             value={name} onChange={(e) => setName(e.target.value)}
             placeholder="Jane's Coffee Shop"/>
      {suggested && (
        <div className="mt-2 flex items-center justify-between text-xs">
          <span className="text-ink-500">
            Detected from your QR: <span className="font-semibold text-ink-700">{suggested}</span>
          </span>
          {name !== suggested && (
            <button onClick={() => setName(suggested)}
                    className="font-semibold text-brand-600 hover:underline">
              Use this
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function Done({ activation }: { activation: ActivationResult | null }) {
  function copy() {
    if (!activation) return;
    navigator.clipboard.writeText(activation.apiKey);
    toast.success("Copied!");
  }
  function openPdf() {
    if (!activation) return;
    // Plain URL, opens in a new tab.
    const url = `https://apicheckpayment.onrender.com/docs/key.pdf?key=${encodeURIComponent(activation.apiKey)}`;
    window.open(url, "_blank", "noopener,noreferrer");
  }
  return (
    <div className="card card-pad text-center bg-gradient-to-br from-accent-50 via-white to-brand-50">
      <div className="mx-auto h-16 w-16 rounded-full bg-accent-500 grid place-items-center shadow-glow">
        <Check size={32} className="text-white"/>
      </div>
      <h2 className="mt-5 text-2xl font-extrabold">All done!</h2>
      <p className="mt-1 text-ink-600 text-sm">
        Your <strong>{activation?.planType}</strong> plan is active for {activation?.days} days.
        <span className="badge-success ml-2">FREE</span>
      </p>

      {activation && (
        <div className="mt-6 mx-auto max-w-md rounded-2xl bg-ink-900 p-5 text-left shadow-glow">
          <div className="text-[10px] uppercase tracking-widest text-ink-400 font-bold">API key</div>
          <code className="mt-1 block text-emerald-300 font-mono text-sm break-all select-all">
            {activation.apiKey}
          </code>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button onClick={copy} className="btn-secondary !bg-white/10 !ring-white/20 !text-white">
              <Copy size={14}/> Copy key
            </button>
            <button onClick={openPdf} className="btn-primary btn-on-dark">
              <Download size={14}/> Get PDF guide
            </button>
          </div>
          <p className="mt-3 text-xs text-ink-400">
            Save this now — we won't show it again.
            Use it as <code className="font-mono">X-API-Key</code> in your requests.
          </p>
        </div>
      )}

      {/* Telegram notifications — opt-in, dismissible. */}
      {activation && (
        <div className="mt-5 mx-auto max-w-md text-left">
          <TelegramNotifyPanel apiKey={activation.apiKey}/>
        </div>
      )}

      <div className="mt-6 flex justify-center gap-2 flex-wrap">
        <a href="/app/test" className="btn-secondary">Test this key</a>
        <a href="/app/transactions" className="btn-primary">See transactions</a>
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────── */
/*  ExistingKeyGate — short-circuit the wizard when the user      */
/*  already has an active key. One-key-per-user policy.            */
/* ────────────────────────────────────────────────────────────── */

interface ExistingKey {
  id: string;
  key: string;
  label?: string | null;
  expiresAt?: string;
  banks?: string[];
}

function ExistingKeyGate({
  existing, extraCount, onUseExisting, onTestExisting, onMintAnyway,
}: {
  existing: ExistingKey;
  extraCount: number;
  onUseExisting: () => void;
  onTestExisting: () => void;
  onMintAnyway: () => void;
}) {
  const [revealed, setRevealed] = useState(false);

  const days = existing.expiresAt
    ? Math.max(0, Math.ceil(
        (new Date(existing.expiresAt).getTime() - Date.now()) / 86_400_000))
    : null;

  const banks = (existing.banks ?? []).filter(Boolean) as BankType[];

  function maskKey(k: string) {
    if (!k.startsWith("sk_")) return k;
    if (k.length <= 12) return k;
    return `${k.slice(0, 7)}${"•".repeat(20)}${k.slice(-4)}`;
  }

  function copyKey() {
    navigator.clipboard.writeText(existing.key);
    toast.success("Key copied");
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
      className="relative overflow-hidden rounded-3xl bg-white ring-1 ring-ink-200 shadow-soft"
    >
      {/* hairline top accent */}
      <div className="h-px bg-gradient-to-r from-transparent via-ink-900/30 to-transparent"/>

      <div className="grid md:grid-cols-[1.1fr_1fr]">
        {/* Left: copy */}
        <div className="p-7 md:p-9 border-b md:border-b-0 md:border-r border-ink-100">
          <div className="inline-flex items-center gap-1.5 rounded-full bg-ink-900 px-2.5 py-1 text-[10px] font-bold uppercase tracking-widest text-white">
            <Shield size={10}/> One key per account
          </div>

          <h2 className="mt-4 text-[28px] leading-[1.1] font-bold tracking-tight text-ink-900">
            You already have an active key.
          </h2>
          <p className="mt-3 text-[15px] leading-relaxed text-ink-500 max-w-md">
            To keep accounts simple and signing reliable, every account holds
            exactly one live <code className="font-mono text-[13px] bg-ink-100 px-1.5 py-0.5 rounded text-ink-800">sk_</code> key
            at a time. Use the one you have, test it, or remove it first to mint a new one.
          </p>

          <div className="mt-7 grid grid-cols-2 gap-2.5">
            <button
              onClick={onUseExisting}
              className="group relative overflow-hidden rounded-xl bg-ink-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-black"
            >
              <span className="relative z-10 flex items-center justify-center gap-1.5">
                Manage in API Keys
                <ArrowRight size={14} className="transition group-hover:translate-x-0.5"/>
              </span>
              {/* shine sweep */}
              <span className="pointer-events-none absolute inset-y-0 -left-1/3 w-1/3 bg-gradient-to-r from-transparent via-white/15 to-transparent translate-x-0 group-hover:translate-x-[400%] transition-transform duration-700 ease-out"/>
            </button>
            <button
              onClick={onTestExisting}
              className="rounded-xl bg-white px-4 py-3 text-sm font-semibold text-ink-900 ring-1 ring-ink-200 transition hover:bg-ink-50 hover:ring-ink-300 inline-flex items-center justify-center gap-1.5"
            >
              <Zap size={14}/> Test this key
            </button>
          </div>

          <button
            onClick={onMintAnyway}
            className="mt-4 inline-flex items-center gap-1 text-[13px] font-medium text-ink-500 hover:text-ink-900 transition"
          >
            <Plus size={12}/> I need to mint a new one →
          </button>

          {extraCount > 0 && (
            <p className="mt-3 text-[11px] text-ink-400">
              {extraCount} additional key{extraCount === 1 ? "" : "s"} on this
              account. Remove some from API Keys to mint a fresh one.
            </p>
          )}
        </div>

        {/* Right: live key card (dark) */}
        <div className="relative p-7 md:p-9 bg-gradient-to-br from-[#0c1020] via-[#0a0d1a] to-[#06080f] text-white">
          {/* subtle radial */}
          <div
            aria-hidden
            className="pointer-events-none absolute -top-24 -right-20 h-60 w-60 rounded-full opacity-40 blur-3xl"
            style={{ background: "radial-gradient(circle, rgba(58,85,255,0.35), transparent 70%)" }}
          />

          {/* gutter line numbers vibe */}
          <div className="relative">
            <div className="flex items-center justify-between">
              <div className="text-[10px] uppercase tracking-[0.18em] text-white/50 font-mono">
                active_key.sk
              </div>
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-semibold text-emerald-300 ring-1 ring-emerald-400/30">
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse"/>
                live
              </span>
            </div>

            <div className="mt-3">
              <div className="text-[11px] text-white/40 font-mono">label</div>
              <div className="text-[15px] font-semibold tracking-tight truncate">
                {existing.label || "Untitled key"}
              </div>
            </div>

            <div className="mt-4">
              <div className="text-[11px] text-white/40 font-mono">key</div>
              <div className="mt-1 flex items-center gap-1.5 rounded-lg bg-white/5 ring-1 ring-white/10 px-2.5 py-2">
                <code className="flex-1 font-mono text-[13px] text-emerald-300 break-all">
                  {revealed ? existing.key : maskKey(existing.key)}
                </code>
                <button
                  onClick={() => setRevealed((v) => !v)}
                  className="rounded-md p-1.5 text-white/60 hover:text-white hover:bg-white/10 transition"
                  title={revealed ? "Hide" : "Reveal"}
                >
                  {revealed ? <EyeOff size={13}/> : <Eye size={13}/>}
                </button>
                <button
                  onClick={copyKey}
                  className="rounded-md p-1.5 text-white/60 hover:text-white hover:bg-white/10 transition"
                  title="Copy"
                >
                  <Copy size={13}/>
                </button>
              </div>
            </div>

            {banks.length > 0 && (
              <div className="mt-4">
                <div className="text-[11px] text-white/40 font-mono">banks</div>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {banks.map((b) => (
                    <span
                      key={b}
                      className="inline-flex items-center gap-1 rounded-md bg-white/5 ring-1 ring-white/10 px-2 py-0.5 text-[11px] font-mono text-white/85"
                    >
                      {b}
                    </span>
                  ))}
                </div>
              </div>
            )}

            <div className="mt-4 flex items-center gap-3 text-[11px]">
              <span className="inline-flex items-center gap-1 text-white/50">
                <Clock size={11}/>
                {days === null
                  ? "no expiry"
                  : days === 0
                    ? <span className="text-rose-300">expires today</span>
                    : days <= 7
                      ? <span className="text-amber-300">{days} day{days === 1 ? "" : "s"} left</span>
                      : <span className="text-emerald-300/90">{days} days left</span>}
              </span>
            </div>
          </div>
        </div>
      </div>
    </motion.div>
  );
}
 