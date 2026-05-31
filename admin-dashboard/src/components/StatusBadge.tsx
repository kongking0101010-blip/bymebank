import clsx from "clsx";

type Status = "PENDING" | "PROCESSING" | "PAID" | "FAILED" | "EXPIRED" | "REFUNDED" | "CANCELLED";

export function StatusBadge({ status }: { status: Status }) {
  const map: Record<Status, string> = {
    PENDING:    "badge-warning",
    PROCESSING: "badge-info",
    PAID:       "badge-success",
    FAILED:     "badge-danger",
    EXPIRED:    "badge-muted",
    REFUNDED:   "badge-muted",
    CANCELLED:  "badge-muted",
  };
  return (
    <span className={clsx(map[status])}>
      <span className="h-1.5 w-1.5 rounded-full bg-current"/>
      {status}
    </span>
  );
}
