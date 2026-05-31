import { Loader2 } from "lucide-react";

/**
 * Production-grade KHQR receipt card — ported verbatim from the bot's
 * /pay page (apicheckpayment.onrender.com) so the visual identity is
 * identical wherever a KHQR is shown across the product.
 *
 * Fixed dimensions: card 273×396, QR 195×195, red header tab 42.6562px tall.
 * Relies on the .qr-card / .dashed-line / .red-triangle / .khqr-fade-in
 * utilities defined in src/index.css.
 */

const KhqrLogoSvg = () => (
  <svg width="60" height="14" viewBox="0 0 60 14" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <path d="M39.006 5.19439V9.59764H34.5318C34.0729 9.59764 33.7288 9.2307 33.7288 8.80731V5.22264C33.7288 4.77103 34.1016 4.43231 34.5318 4.43231H38.1743C38.6619 4.40408 39.006 4.74278 39.006 5.19439Z" fill="white"/>
    <path d="M59.9717 6.97176H57.7345C57.7345 4.34676 55.5548 2.20159 52.8875 2.20159C50.7651 2.20159 48.9008 3.55645 48.2699 5.53225C48.1265 6.01209 48.0404 6.49192 48.0404 6.97176V13.9718H47.9831C46.7785 13.9718 45.8033 13.0121 45.8033 11.8266V6.97176H45.832C45.832 5.05241 46.6351 3.21773 48.0691 1.89112C49.3884 0.677406 51.1093 0 52.9162 0C56.8168 0 59.9717 3.13305 59.9717 6.97176Z" fill="white"/>
    <path d="M59.9999 13.9718L56.845 14L56.0706 13.2379L54.3497 11.5444L51.9692 9.20166H55.1241L59.9999 13.9718Z" fill="white"/>
    <path d="M39.7517 11.7702H33.0117C32.1799 11.7702 31.5203 11.121 31.5203 10.3024V3.66936C31.5203 2.85081 32.1799 2.20159 33.0117 2.20159H39.7517C40.5834 2.20159 41.2431 2.85081 41.2431 3.66936V10.3024L43.4802 12.504V2.14515C43.4802 0.959671 42.505 0 41.3005 0H31.4629C30.2583 0 29.2832 0.959671 29.2832 2.14515V11.8266C29.2832 13.0121 30.2583 13.9718 31.4629 13.9718H41.9888L39.7517 11.7702Z" fill="white"/>
    <path d="M12.3614 14H9.20656L2.60996 7.47984V14H0V0H2.60996V6.2379L8.94843 0H12.046L5.16255 6.71772L12.3614 14Z" fill="white"/>
    <path d="M24.1492 0H26.7018V14H24.1492V7.93145H16.8643V14H14.3117V0H16.8643V5.84273H24.1492V0Z" fill="white"/>
  </svg>
);

export interface KhqrReceiptCardProps {
  /** Bank name shown in the header (e.g. "ACLEDA Bank", "Bakong"). */
  bank: string;
  /** Caps merchant name displayed under the tear line. */
  merchantName: string;
  /** Numeric amount, e.g. 1.50. */
  amount: number;
  /** ISO currency, e.g. "USD" / "KHR". */
  currency?: string;
  /** base64 PNG (with or without `data:image/png;base64,` prefix). */
  qrImage?: string | null;
  /** Skeleton spinner while fetching. */
  loading?: boolean;
  /** Overlay the rotated PAID stamp on the QR. */
  paid?: boolean;
  /** Footnote shown under the card (defaults to "Scan with…"). */
  footer?: string;
}

export function KhqrReceiptCard({
  bank, merchantName, amount, currency = "USD",
  qrImage, loading = false, paid = false,
  footer,
}: KhqrReceiptCardProps) {
  const qrSrc = qrImage
    ? (qrImage.startsWith("data:") ? qrImage : `data:image/png;base64,${qrImage}`)
    : null;

  const heading = `${(bank || "KHQR").toUpperCase()} KHQR`;
  const footerText = footer ?? "Scan with ABA Mobile, or any KHQR app";

  return (
    <div
      role="region"
      aria-label={`${bank || "KHQR"} receipt for ${amount.toFixed(currency === "KHR" ? 0 : 2)} ${currency}`}
      className="font-sans bg-white text-[rgb(8,27,55)] w-[320px] rounded-[20px] p-5 shadow-[0_30px_60px_-20px_rgba(0,0,0,0.4),0_8px_24px_-8px_rgba(0,0,0,0.15)] khqr-fade-in ring-1 ring-black/[0.04]"
    >
      {/* Heading above the card */}
      <div className="text-center pb-4">
        <h3 className="text-[18px] font-extrabold tracking-tight text-ink-900 leading-tight">
          {heading}
        </h3>
        <div className="mt-0.5 text-[10px] uppercase tracking-[0.18em] font-mono text-ink-400">
          Pay to activate
        </div>
      </div>

      <div className="flex justify-center">
        {/* Fixed receipt: 273 × 396 */}
        <div className="rounded-[22px] qr-card h-[396px] w-[273px] flex flex-col bg-white">
          <div className="mb-8">
            {/* Red header tab */}
            <div className="flex items-center justify-center bg-[rgb(226,26,26)] rounded-t-[21px] h-[42.6562px]">
              <KhqrLogoSvg/>
            </div>

            {/* Red triangle notch — top-right */}
            <div className="flex justify-end">
              <div className="red-triangle"/>
            </div>

            {/* Merchant + amount with dashed tear line */}
            <div className="dashed-line py-[15px] px-3 pl-[42px]">
              <span className="text-[10px] font-semibold tracking-wide">{(merchantName || "—").toUpperCase()}</span>
              <div className="mt-1">
                <div className="flex items-center text-sm font-bold w-full">
                  <span className="tabular-nums">{amount.toFixed(currency === "KHR" ? 0 : 2)}</span>
                  <span className="text-[8px] font-normal ml-2 text-gray-500">{currency}</span>
                </div>
              </div>
            </div>
          </div>

          {/* QR area */}
          <div className="flex justify-center items-center relative flex-1">
            <div className="relative -mt-[5px]">
              {loading ? (
                <div className="flex items-center justify-center w-[195px] h-[195px]">
                  <Loader2 className="w-12 h-12 text-gray-400 animate-spin"/>
                </div>
              ) : qrSrc ? (
                <div className={paid ? "relative" : "qr-scan-frame relative"}>
                  {/* Live-scan animation overlay — only while awaiting payment */}
                  {!paid && (
                    <>
                      <span className="qr-scan-glow"/>
                      <span className="qr-scan-corner tl"/>
                      <span className="qr-scan-corner tr"/>
                      <span className="qr-scan-corner bl"/>
                      <span className="qr-scan-corner br"/>
                      <span className="qr-scan-beam"/>
                    </>
                  )}
                  <img
                    src={qrSrc}
                    alt={`${bank || "KHQR"} payment QR`}
                    className="m-auto h-auto max-w-[195px] w-[195px] select-none pointer-events-none relative z-[1]"
                    draggable={false}
                    onContextMenu={(e) => e.preventDefault()}
                  />
                </div>
              ) : (
                <div className="flex flex-col items-center justify-center w-[195px] h-[195px] rounded-md bg-gray-50 ring-1 ring-dashed ring-gray-200 text-gray-400">
                  <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" className="opacity-60">
                    <rect x="3" y="3" width="7" height="7" rx="1"/>
                    <rect x="14" y="3" width="7" height="7" rx="1"/>
                    <rect x="3" y="14" width="7" height="7" rx="1"/>
                    <path d="M14 14h3m4 0h-1m-3 4h7m-7 3h7M14 18v3"/>
                  </svg>
                  <span className="mt-2 text-[10px] uppercase tracking-[0.18em] font-mono">awaiting</span>
                </div>
              )}
              {paid && qrSrc && (
                <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                  <span className="px-4 py-1 rounded-md border-2 border-emerald-600 text-emerald-600 font-extrabold tracking-widest text-2xl rotate-[-8deg] bg-white/70">
                    PAID
                  </span>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="text-center pt-4 pb-1">
        <p className="text-[12px] text-gray-500">{footerText}</p>
      </div>
    </div>
  );
}
