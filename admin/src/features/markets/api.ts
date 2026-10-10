import { api } from "@/lib/api";

export type Status = "PRE_TRADING" | "TRADING" | "HALTED" | "CANCEL_ONLY" | "POST_TRADING" | "DELISTED";

/** Amounts are decimal strings; null is "no limit". */
export type Limits = {
  minQty: string; maxQty: string; minNotional: string; maxNotional: string | null;
  marketMinQty: string | null; marketMaxQty: string | null; minQuoteQty: string | null; maxQuoteQty: string | null;
  priceBandBps: number | null; maxOpenOrders: number; maxOpenConditionalOrders: number;
  marketOrdersEnabled: boolean; stopOrdersEnabled: boolean;
};

/** The pair, partition, tick and step are fixed at listing; only the limits change. */
export type Market = {
  id: number; symbol: string; base: string; quote: string; status: Status; commandPartition: number;
  priceTick: string; qtyStep: string; limits: Limits; createdAt: string;
};
export type AssetOption = { id: number; symbol: string; name: string };
export type Listing = { markets: Market[]; assets: AssetOption[]; partitionCount: number };

export type NewMarket = {
  baseAssetId: number; quoteAssetId: number; commandPartition: number; priceTick: string; qtyStep: string; limits: Limits;
};

export const fetchMarkets = () => api<Listing>("/admin/markets");
export const fetchMarket = (id: string) => api<Market>(`/admin/markets/${id}`);
export const listMarket = (market: NewMarket, totp: string) =>
  api<Market>("/admin/markets", { method: "POST", body: market, totp });
export const setStatus = (id: number, status: "TRADING" | "CANCEL_ONLY", totp: string) =>
  api<Market>(`/admin/markets/${id}/status`, { method: "POST", body: { status }, totp });
export const setLimits = (id: number, limits: Limits, totp: string) =>
  api<Market>(`/admin/markets/${id}/limits`, { method: "PATCH", body: limits, totp });
