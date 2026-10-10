import { api } from "@/lib/api";

/** Fees earned and rebates paid over one window, as positive decimal strings. */
export type Flow = { fees: string; rebates: string; net: string };

export type AssetLine = {
  symbol: string;
  name: string;
  displayDecimals: number;
  /** What the books say customers hold. A liability, not reserves: nothing here is custody. */
  customerAvailable: string;
  customerLocked: string;
  customerTotal: string;
  holders: number;
  feeAccount: string;
  treasuryAccount: string;
  periods: Record<Period, Flow>;
};

export type Period = "today" | "week" | "month" | "all";

/** `sweptAt` is null until fee revenue has ever reached the account. */
export type Treasury = { assets: AssetLine[]; sweptAt: string | null };

export const fetchTreasury = () => api<Treasury>("/admin/treasury");

/** One UTC day of one asset's fee flow. Every day in the range is present; a quiet day is "0". */
export type Day = { day: string; fees: string; rebates: string };

export const fetchRevenue = (asset: string, days = 30) =>
  api<Day[]>(`/admin/treasury/revenue?asset=${encodeURIComponent(asset)}&days=${days}`);

/** One move of the exchange's own money, as its audit row recorded it. `to` is null for fees moved to the treasury. */
export type Move = { at: string; kind: "FEES_TO_TREASURY" | "PAYOUT"; admin: string | null; asset: string; amount: string; to: string | null; reason: string };

/**
 * Both moves carry an id made here, once per dialog: a retried request is then the same move on the
 * server, and money moves once (a mutation is never retried anyway, docs/reference/frontend-session.md).
 */
export const moveFees = (body: { id: string; asset: string; amount: string; reason: string }, totp: string) =>
  api<Treasury>("/admin/treasury/fee-moves", { method: "POST", body, totp });

export const payOut = (body: { id: string; customer: string; asset: string; amount: string; reason: string }, totp: string) =>
  api<Treasury>("/admin/treasury/payouts", { method: "POST", body, totp });

export const fetchMoves = () => api<Move[]>("/admin/treasury/moves");
