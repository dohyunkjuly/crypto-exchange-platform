import type { OrderStatus } from "./api";

const LOOK: Record<OrderStatus, [string, string]> = {
  PENDING_TRIGGER: ["Waiting for trigger", "pill-off"],
  NEW: ["Open", "pill-ok"],
  PARTIALLY_FILLED: ["Partly filled", "pill-ok"],
  PENDING_CANCEL: ["Cancelling", "pill-warn"],
  FILLED: ["Filled", "pill-off"],
  CANCELED: ["Cancelled", "pill-off"],
  EXPIRED: ["Expired", "pill-off"],
  REJECTED: ["Rejected", "pill-bad"],
};

export function OrderStatusPill({ status }: { status: OrderStatus }) {
  const [label, tone] = LOOK[status];
  return <span className={`pill ${tone}`}>{label}</span>;
}
