import clsx from "clsx";
import { useLogo } from "@/store/branding";

interface LogoProps {
  className?: string;
  /** Hide the wordmark (just show the square mark). */
  mark?: boolean;
  /** Use light text — for placement on dark surfaces (sidebar, login). */
  dark?: boolean;
}

/**
 * Brand mark.
 *
 * Reads `branding.brand` from the store — if an admin has uploaded a logo
 * via the Telegram bot's `/setlogo brand` command, we render that image.
 * Otherwise we fall back to the SVG mark with the brand gradient.
 */
export function Logo({ className, mark, dark }: LogoProps) {
  const brand = useLogo("brand");

  return (
    <div className={clsx("flex items-center gap-2.5", className)}>
      <div className="relative h-9 w-9 rounded-xl shadow-glow overflow-hidden ring-1 ring-white/10
                      bg-gradient-to-br from-brand-600 to-brand-900 flex items-center justify-center">
        {brand ? (
          <img
            src={brand}
            alt="Byme Bank"
            className="h-full w-full object-cover"
            loading="lazy"
          />
        ) : (
          <svg viewBox="0 0 24 24" className="h-5 w-5 text-white" fill="none"
               stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 9l9-6 9 6"/>
            <path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9"/>
            <path d="M10 14h4"/>
            <path d="M12 12v4"/>
          </svg>
        )}
        <div className={clsx(
          "absolute -bottom-1 -right-1 h-3 w-3 rounded-full bg-accent-400 ring-2 animate-pulse",
          dark ? "ring-[#0a0d28]" : "ring-white",
        )}/>
      </div>
      {!mark && (
        <div className="leading-tight">
          <div className={clsx(
            "text-base font-extrabold tracking-tight inline-flex items-center",
            dark ? "text-white" : "text-ink-900",
          )}>
            <span className={clsx("brand-wordmark", dark && "dark")}>
              Byme Bank
            </span>
            <span className="brand-pulse-dot" aria-hidden/>
          </div>
          <div className={clsx(
            "text-[10px] font-medium uppercase tracking-widest",
            dark ? "text-white/55" : "text-ink-400",
          )}>
            Payment Gateway
          </div>
        </div>
      )}
    </div>
  );
}
