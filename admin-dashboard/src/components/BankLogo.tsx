import clsx from "clsx";
import { useLogo, type LogoSlot } from "@/store/branding";

/**
 * Renders the admin-uploaded logo for a bank slot, falling back to a colored
 * dot + initial when no logo has been uploaded yet.
 *
 * Usage:
 *   <BankLogo bank="aba" size={28}/>
 *   <BankLogo bank="wing" size={20} square/>
 */
export type BankSlot = Exclude<LogoSlot, "brand">;

const BANK_META: Record<
  BankSlot,
  { color: string; ring: string; label: string; initial: string }
> = {
  aba:    { color: "bg-rose-500",    ring: "ring-rose-300/50",    label: "ABA",    initial: "A" },
  acleda: { color: "bg-emerald-500", ring: "ring-emerald-300/50", label: "ACLEDA", initial: "C" },
  wing:   { color: "bg-sky-500",     ring: "ring-sky-300/50",     label: "Wing",   initial: "W" },
  bakong: { color: "bg-violet-500",  ring: "ring-violet-300/50",  label: "Bakong", initial: "B" },
};

interface Props {
  bank: BankSlot;
  size?: number;       // px, default 24
  square?: boolean;    // default true (rounded-lg). Pass false for rounded-full.
  className?: string;
}

export function BankLogo({ bank, size = 24, square = true, className }: Props) {
  const url = useLogo(bank);
  const meta = BANK_META[bank];
  const radius = square ? "rounded-lg" : "rounded-full";

  if (url) {
    return (
      <img
        src={url}
        alt={meta.label}
        title={meta.label}
        width={size}
        height={size}
        className={clsx(
          radius,
          "object-cover ring-1 ring-black/5 bg-white",
          className,
        )}
        style={{ width: size, height: size }}
        loading="lazy"
      />
    );
  }

  // Fallback: solid colored tile with the bank's initial.
  const fontSize = Math.max(10, Math.round(size * 0.45));
  return (
    <span
      className={clsx(
        radius,
        meta.color,
        meta.ring,
        "ring-1 inline-flex items-center justify-center text-white font-bold leading-none",
        className,
      )}
      style={{ width: size, height: size, fontSize }}
      title={meta.label}
      aria-label={meta.label}
    >
      {meta.initial}
    </span>
  );
}
