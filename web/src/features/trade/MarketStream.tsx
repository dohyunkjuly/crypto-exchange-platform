"use client";

import { createContext, useContext, useEffect, useRef, useState } from "react";
import { BffSocket, type Frame } from "@/lib/bff/ws";

const Ctx = createContext<BffSocket | null>(null);

/** One public market socket for the trading screen; panels subscribe to channels on it. */
export function MarketStream({ children }: { children: React.ReactNode }) {
  const [socket] = useState(() => new BffSocket("/bff/ws/market"));
  useEffect(() => {
    socket.connect();
    return () => socket.close();
  }, [socket]);
  return <Ctx.Provider value={socket}>{children}</Ctx.Provider>;
}

/** The socket itself, for a caller that needs its reconnect signal rather than a channel. */
export function useMarketSocket(): BffSocket | null { return useContext(Ctx); }

export type Channel = "ticker" | "trade" | "depth" | "kline";
export type Subscription = { channel: Channel; symbol: string; interval?: string; grouping?: string };

/** Whether a frame belongs to a subscription: the type is the channel, and the payload names its symbol
 *  (and interval, for klines). A depth frame for BTC must never land in ETH's book. */
export function frameMatches(frame: Frame, sub: Subscription): boolean {
  if (frame.type !== sub.channel) return false;
  const data = frame.data as { symbol?: string; interval?: string } | null;
  if (!data || data.symbol !== sub.symbol) return false;
  return sub.channel !== "kline" || data.interval === sub.interval;
}

const keyOf = (s: Subscription) => `${s.channel}:${s.symbol}:${s.interval ?? ""}:${s.grouping ?? ""}`;

/** Subscribes for the component's life and hands it every matching payload. The handler may change
 *  without resubscribing. */
export function useMarketChannel<T>(sub: Subscription | null, onData: (data: T) => void) {
  const socket = useContext(Ctx);
  const handler = useRef(onData);
  useEffect(() => { handler.current = onData; });
  const key = sub ? keyOf(sub) : null;
  useEffect(() => {
    if (!socket || !sub || !key) return;
    const off = socket.onFrame((frame) => { if (frameMatches(frame, sub)) handler.current(frame.data as T); });
    const payload = { channel: sub.channel, symbol: sub.symbol, ...(sub.interval && { interval: sub.interval }), ...(sub.grouping && { grouping: sub.grouping }) };
    const unsubscribe = socket.subscribe(key, { action: "subscribe", ...payload }, { action: "unsubscribe", ...payload });
    return () => { off(); unsubscribe(); };
    // The key captures every field of `sub`; the object identity does not matter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [socket, key]);
}
