import { ArrowRight } from "lucide-react";
import { Link } from "react-router-dom";
import type { ComponentProps, ReactNode } from "react";

/**
 * PrimaryCTA — premium "magnetic" call-to-action button.
 *
 * Design notes
 * ────────────
 *  • Idle:   tight gradient pill with a soft brand glow underneath.
 *  • Hover:  subtle lift, glow grows, a diagonal shine sweeps across,
 *            arrow nudges right and a faint ghost arrow trails behind.
 *  • Active: presses into the surface (translateY 0).
 *  • Focus:  keyboard ring uses the brand color.
 *
 * The motion is CSS-driven so it stays buttery (no React renders),
 * and it respects `prefers-reduced-motion`.
 */
type Variant = "default" | "compact";
type CommonProps = {
  children: ReactNode;
  className?: string;
  /** Show the trailing arrow (default true). */
  arrow?: boolean;
  variant?: Variant;
};

type AsLink = CommonProps & { to: string } & Omit<ComponentProps<typeof Link>, "to" | "className" | "children">;
type AsButton = CommonProps & { to?: undefined } & Omit<ComponentProps<"button">, "className" | "children">;

export function PrimaryCTA(props: AsLink | AsButton) {
  const { children, className = "", arrow = true, variant = "default", ...rest } = props;

  const sizeCx =
    variant === "compact"
      ? "px-4 py-2 text-[13px]"
      : "px-5 py-2.5 text-sm";

  const inner = (
    <span className={`pcta ${sizeCx} ${className}`}>
      {/* gradient backdrop layers */}
      <span className="pcta-bg"   aria-hidden/>
      <span className="pcta-glow" aria-hidden/>
      <span className="pcta-shine" aria-hidden/>

      {/* content */}
      <span className="pcta-label">{children}</span>

      {arrow && (
        <span className="pcta-arrow" aria-hidden>
          {/* trailing ghost arrow */}
          <ArrowRight size={14} className="pcta-arrow-ghost"/>
          <ArrowRight size={14} className="pcta-arrow-main"/>
        </span>
      )}
    </span>
  );

  if ("to" in props && props.to) {
    return <Link {...(rest as Omit<ComponentProps<typeof Link>, "to" | "className" | "children">)} to={props.to}>{inner}</Link>;
  }
  return <button {...(rest as ComponentProps<"button">)}>{inner}</button>;
}
