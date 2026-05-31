import clsx from "clsx";
import { BankLogo, type BankSlot } from "./BankLogo";

export type BankType = "ABA" | "ACLEDA" | "WING" | "BAKONG";

const STYLES: Record<BankType, { cls: string; slot: BankSlot; label: string }> = {
  ABA:    { cls: "chip-bank-aba",    slot: "aba",    label: "ABA Bank" },
  ACLEDA: { cls: "chip-bank-acleda", slot: "acleda", label: "ACLEDA" },
  WING:   { cls: "chip-bank-wing",   slot: "wing",   label: "Wing" },
  BAKONG: { cls: "chip-bank-bakong", slot: "bakong", label: "Bakong" },
};

/**
 * Pill-shaped bank chip. When an admin has uploaded a per-bank logo the chip
 * shows the actual icon; otherwise it falls back to a colored dot.
 */
export function BankBadge({ bank, className }: { bank: BankType; className?: string }) {
  const s = STYLES[bank];
  return (
    <span className={clsx(s.cls, "inline-flex items-center gap-1.5", className)}>
      <BankLogo bank={s.slot} size={14} square/>
      {s.label}
    </span>
  );
}
