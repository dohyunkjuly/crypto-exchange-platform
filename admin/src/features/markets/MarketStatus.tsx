import type { Status } from "./api";

const LOOK: Record<Status, [string, string]> = {
  PRE_TRADING: ["Not open", "pill-off"],
  TRADING: ["Trading", "pill-ok"],
  HALTED: ["Halted", "pill-warn"],
  CANCEL_ONLY: ["Cancel-only", "pill-warn"],
  POST_TRADING: ["Closed", "pill-off"],
  DELISTED: ["Delisted", "pill-off"],
};

export function MarketStatus({ status }: { status: Status }) {
  const [label, tone] = LOOK[status];
  return <span className={`pill ${tone}`}>{label}</span>;
}
