import { api } from "@/lib/api";

export type OrderStatus =
  | "PENDING_TRIGGER" | "NEW" | "PARTIALLY_FILLED" | "PENDING_CANCEL"
  | "FILLED" | "CANCELED" | "REJECTED" | "EXPIRED";

/** Amounts are decimal strings. A market order has no price; one sized in quote has no quantity. */
export type Order = {
  id: string; symbol: string; base: string; quote: string; userId: number; userEmail: string | null;
  side: "BUY" | "SELL"; type: string; status: OrderStatus;
  price: string | null; stopPrice: string | null; quantity: string | null; quoteOrderQty: string | null;
  filled: string; quoteFilled: string; clientOrderId: string | null; createdAt: string; updatedAt: string;
};
export type Fill = {
  tradeId: string; price: string; quantity: string; quoteQuantity: string; maker: boolean; fee: string; feeAsset: string | null; executedAt: string;
};
export type Page = { items: Order[]; total: number; page: number; size: number };
export type Detail = { order: Order; fills: Fill[] };

/** `from` and `to` are instants; `to` is exclusive. */
export type Filters = { customer: string; symbol: string; side: string; state: string; from: string; to: string };

export function fetchOrders(f: Filters, page: number, size: number) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  for (const [k, v] of Object.entries(f)) if (v) params.set(k, v);
  return api<Page>(`/admin/orders?${params}`);
}
export const fetchOrder = (id: string) => api<Detail>(`/admin/orders/${id}`);
