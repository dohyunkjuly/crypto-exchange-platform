"use client";

import { useSyncExternalStore } from "react";

const KEY = "trading-account";
const listeners = new Set<() => void>();
let cache: string | null = null;
const read = () => { try { return sessionStorage.getItem(KEY) ?? ""; } catch { return ""; } };
const snapshot = () => (cache ??= read());

/** Which account the trading screen trades as: the customer's own, or their lead account. */
export type TradingAccount = "lead" | undefined;

/** Outside a component too: the lead page sets it on the way to the trading screen. */
export function setTradingAccount(account: TradingAccount) {
  cache = account ?? "";
  try { sessionStorage.setItem(KEY, cache); } catch { /* not persisted; still applied this session */ }
  listeners.forEach((cb) => cb());
}

/**
 * Kept for this tab only, so it survives a change of pair and a new tab starts on the
 * customer's own account: trading as an account one did not mean to is worse than choosing again.
 */
export function useTradingAccount(): [TradingAccount, (account: TradingAccount) => void] {
  const raw = useSyncExternalStore((cb) => { listeners.add(cb); return () => listeners.delete(cb); }, snapshot, () => "");
  return [raw === "lead" ? "lead" : undefined, setTradingAccount];
}
