"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchTickers, type Ticker } from "@/features/markets/api";
import { useMarketChannel } from "./MarketStream";

/** The market's 24h summary: the tickers query for the first paint, then every ticker frame after. */
export function useTicker(symbol: string): Ticker | undefined {
  const { data } = useQuery({ queryKey: ["tickers"], queryFn: ({ signal }) => fetchTickers(signal), staleTime: 60_000 });
  const [live, setLive] = useState<Ticker | null>(null);
  useMarketChannel<Ticker>({ channel: "ticker", symbol }, setLive);
  return live?.symbol === symbol ? live : data?.find((t) => t.symbol === symbol);
}
