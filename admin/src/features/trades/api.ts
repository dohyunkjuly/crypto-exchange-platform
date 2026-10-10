import { api, type Chunk } from "@/lib/api";

/** One side of a trade. A fee of zero has no asset. */
export type Party = { userId: number; email: string | null; orderId: string; fee: string; feeAsset: string | null };

export type Trade = {
  id: string; symbol: string; base: string; quote: string; price: string; quantity: string; quoteQuantity: string;
  buyer: Party; seller: Party; buyerIsMaker: boolean; executedAt: string;
};

/** `from` and `to` are instants; `to` is exclusive. */
export type Filters = { customer: string; symbol: string; from: string; to: string };

export function fetchTrades(f: Filters, page: number, size: number) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  for (const [k, v] of Object.entries(f)) if (v) params.set(k, v);
  return api<Chunk<Trade>>(`/admin/trades?${params}`);
}
