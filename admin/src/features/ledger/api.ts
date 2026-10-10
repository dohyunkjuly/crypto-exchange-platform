import { api, type Chunk } from "@/lib/api";

/** Amounts are signed decimal strings: positive in, negative out. */
export type Entry = {
  id: string; userId: number; userEmail: string | null; asset: string; displayDecimals: number; type: string;
  amount: string; balanceAfter: string; referenceType: string | null; referenceId: string | null; createdAt: string;
};

/** Every movement type the ledger records, as staff read it. The keys are what is stored. */
export const TYPES: Record<string, string> = {
  DEPOSIT: "Deposit",
  WITHDRAWAL: "Withdrawal",
  ORDER_LOCK: "Locked for order",
  ORDER_UNLOCK: "Released from order",
  TRADE_CREDIT: "Trade in",
  TRADE_DEBIT: "Trade out",
  FEE: "Fee",
  REBATE: "Rebate",
  TRANSFER_IN: "Transfer in",
  TRANSFER_OUT: "Transfer out",
  ADJUSTMENT: "Adjustment",
  FEE_SWEEP: "Fee sweep",
};

/** `from` and `to` are instants; `to` is exclusive. */
export type Filters = { customer: string; asset: string; type: string; from: string; to: string };

export function fetchLedger(f: Filters, page: number, size: number) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  for (const [k, v] of Object.entries(f)) if (v) params.set(k, v);
  return api<Chunk<Entry>>(`/admin/ledger?${params}`);
}
