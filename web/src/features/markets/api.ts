import { api } from "@/lib/bff/client";

export type SymbolStatus = "PRE_TRADING" | "TRADING" | "HALTED" | "CANCEL_ONLY" | "POST_TRADING" | "DELISTED";

export type MarketSymbol = {
  symbol: string; baseAsset: string; quoteAsset: string; status: SymbolStatus;
  priceTick: string; qtyStep: string; minQty: string; maxQty: string; minNotional: string;
  baseDecimals: number; quoteDecimals: number;
  marketOrdersEnabled: boolean; stopOrdersEnabled: boolean; depthGroupings: string[];
  /** No matching engine is running this market right now: new orders are refused, cancels go through. */
  paused: boolean;
};

/** All money fields are decimal strings. */
export type Ticker = {
  symbol: string; lastPrice: string; openPrice: string; highPrice: string; lowPrice: string;
  priceChange: string; priceChangePercent: string; volume: string; quoteVolume: string;
};

export const fetchSymbols = (signal?: AbortSignal) => api<MarketSymbol[]>("/bff/symbols", { signal });
/** Every market, busiest first. One call; filtering and sorting are the browser's job. */
export const fetchTickers = (signal?: AbortSignal) => api<Ticker[]>("/bff/market/tickers", { signal });

export type Candle = { openTime: string; open: string; high: string; low: string; close: string; volume: string; quoteVolume: string; tradeCount: number };
export type DepthLevel = { price: string; quantity: string };
export type Depth = { symbol: string; lastSequence: number; bids: DepthLevel[]; asks: DepthLevel[] };
export type PublicTrade = { sequence: number; price: string; quantity: string; quoteQuantity: string; takerSide: "BUY" | "SELL"; time: string };
export type CandleInterval = "1m" | "5m" | "15m" | "30m" | "1h" | "2h" | "4h" | "6h" | "8h" | "12h" | "1d" | "1M";

/** `endTime` is the newest bucket wanted (paging back from the oldest bar shown); absent means now. */
export const fetchCandles = (symbol: string, interval: CandleInterval, limit: number, signal?: AbortSignal, endTime?: string) =>
  api<Candle[]>(`/bff/market/${symbol}/candles?interval=${interval}&limit=${limit}${endTime ? `&endTime=${encodeURIComponent(endTime)}` : ""}`, { signal });
export const fetchDepth = (symbol: string, grouping: string | null, limit: number, signal?: AbortSignal) =>
  api<Depth>(`/bff/market/${symbol}/depth?limit=${limit}${grouping ? `&grouping=${grouping}` : ""}`, { signal });
export const fetchRecentTrades = (symbol: string, limit: number, signal?: AbortSignal) =>
  api<PublicTrade[]>(`/bff/market/${symbol}/trades?limit=${limit}`, { signal });
