"use client";

import { useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { useToast } from "@/components/ui/Toast";
import { useT, useTOptional } from "@/lib/i18n";
import { BffSocket } from "@/lib/bff/ws";
import { balancesKey, openOrdersKey, type Balance, type Order } from "./api";
import { applyBalanceChanged, applyOrderUpdate, toastForUpdate, withoutClosed, type BalanceChanged, type OrderUpdate } from "../orders/orderMath";

const sameKey = (a: QueryKey, b: QueryKey) => a.length === b.length && a.every((x, i) => x === b[i]);

/** The shortest gap between two refetches of one list that a burst of frames may cause. */
export const REFETCH_GAP_MS = 1_000;

/**
 * Invalidates at most once per key per gap, and once more when the gap ends if anything
 * arrived meanwhile. A ladder of stop orders firing together is a burst of acceptance frames
 * without rows; a refetch per frame would trip web-bff's rate limit and turn the open-orders
 * panel into "Too many requests". The last frame still lands: the trailing refetch reads
 * whatever the burst left.
 */
export function coalesced(invalidate: (key: QueryKey) => void, gap = REFETCH_GAP_MS) {
  const windows = new Map<string, { timer: ReturnType<typeof setTimeout>; again: boolean }>();
  const open = (id: string, key: QueryKey) => {
    invalidate(key);
    windows.set(id, { again: false, timer: setTimeout(() => {
      const w = windows.get(id);
      windows.delete(id);
      if (w?.again) open(id, key);
    }, gap) });
  };
  return {
    invalidate(key: QueryKey) {
      const id = JSON.stringify(key);
      const w = windows.get(id);
      if (w) w.again = true;
      else open(id, key);
    },
    close() { for (const w of windows.values()) clearTimeout(w.timer); windows.clear(); },
  };
}

/** How many closed-before-seen order ids to remember; a session never needs more. */
const CLOSED_LIMIT = 500;

/**
 * One private socket for the whole signed-in area. No subscribe message: the handshake
 * cookie already says whose events these are. Mounted inside the session gate, so it never
 * opens for a signed-out visitor.
 *
 * Frames are whole state (docs/reference/sockets.md), so each patches the row it concerns in
 * the query cache and nothing is fetched again: an order filling a hundred times is a hundred
 * frames and no requests. The list is fetched once on mount and again on reconnect, when
 * frames may have been missed. History is deliberately not live -- it pages by offset, so a
 * row inserted at the top would shift every later page by one.
 *
 * `account`: the trading screen showing the lead account connects for that account instead.
 * A socket carries one account's frames, and they patch that account's lists only.
 */
export function UserStream({ children, account }: { children: React.ReactNode; account?: "lead" }) {
  const queryClient = useQueryClient();
  const show = useToast();
  const t = useT();
  const tOpt = useTOptional();
  // Held in a ref so the socket effect below keeps its [queryClient] deps: a new t() identity
  // on every render would otherwise tear down and reopen the connection.
  const notify = useRef<(u: OrderUpdate, order?: Order) => void>(() => {});
  useEffect(() => {
    notify.current = (u, order) => {
      const toast = toastForUpdate(u, order);
      if (!toast) return;
      const body = [
        toast.symbol,
        toast.side && t(`side.${toast.side}`),
        toast.quantity && t("toast.filledQty", { quantity: toast.quantity }),
        toast.reason && tOpt(`toast.reason.${toast.reason}`),
      ].filter(Boolean).join(" \u00b7 ");
      show({ tone: toast.tone, title: t(`toast.${toast.kind}`), body });
    };
  });
  useEffect(() => {
    const OPEN_ORDERS = openOrdersKey(account);
    const BALANCES = balancesKey(account);
    const socket = new BffSocket(account ? `/bff/ws/user?account=${account}` : "/bff/ws/user");
    const refetch = coalesced((queryKey) => queryClient.invalidateQueries({ queryKey, exact: true }));
    // Orders the socket closed before the list had them. A refetch started at placement can land
    // after the fill, still saying NEW; anything loaded into the list is stripped of these.
    const closed = new Set<string>();
    const offCache = queryClient.getQueryCache().subscribe((e) => {
      if (e.type !== "updated" || e.action.type !== "success" || !sameKey(e.query.queryKey, OPEN_ORDERS)) return;
      const data = e.query.state.data as Order[] | undefined;
      if (data) { const stripped = withoutClosed(data, closed); if (stripped !== data) queryClient.setQueryData(OPEN_ORDERS, stripped); }
    });
    const offFrame = socket.onFrame((frame) => {
      if (frame.type === "orderUpdate") {
        const u = frame.data as OrderUpdate;
        const listed = queryClient.getQueryData<Order[]>(OPEN_ORDERS);
        // Read the row before applying: a terminal update removes it, and the row is where
        // the side and the order's own numbers live.
        notify.current(u, listed?.find((o) => o.orderId === u.orderId));
        const next = applyOrderUpdate(listed, u);
        if (next === "refetch") refetch.invalidate(OPEN_ORDERS);
        else if (next === "closed") { if (closed.size >= CLOSED_LIMIT) closed.delete(closed.values().next().value!); closed.add(u.orderId); }
        else if (next) queryClient.setQueryData(OPEN_ORDERS, next);
        return;
      }
      // A `fill` frame is not read: the engine's orderUpdate for the same trade already moved the row.
      if (frame.type === "balanceChanged") {
        const next = applyBalanceChanged(queryClient.getQueryData<Balance[]>(BALANCES), frame.data as BalanceChanged);
        if (next) queryClient.setQueryData(BALANCES, next);
      }
    });
    const offReconnect = socket.onReconnect(() => {
      queryClient.invalidateQueries({ queryKey: OPEN_ORDERS, exact: true });
      queryClient.invalidateQueries({ queryKey: BALANCES, exact: true });
    });
    socket.connect();
    return () => { offCache(); offFrame(); offReconnect(); refetch.close(); socket.close(); };
  }, [queryClient, account]);
  return children;
}
