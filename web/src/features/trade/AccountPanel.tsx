"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useSession } from "@/features/auth/session";
import type { MarketSymbol } from "@/features/markets/api";
import { fetchOrders, openOrdersKey } from "@/features/my/shared/api";
import { AssetsTable } from "@/features/my/assets/AssetsTable";
import { inKind, type OpenKind } from "@/features/my/shared/filterMath";
import { OpenOrders, OrderHistory, TradeHistory } from "@/features/my/orders/Orders";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";

type Tab = "open" | "triggers" | "history" | "trades" | "assets";

/** The strip under chart and book: the tabs from /my/orders for the account being traded, defaulting to this pair. */
export function AccountPanel({ market, account }: { market: MarketSymbol; account?: "lead" }) {
  const t = useT();
  const session = useSession();
  const [tab, setTab] = useState<Tab>("open");
  const [thisPair, setThisPair] = useState(true);
  const lock = thisPair ? market.symbol : undefined;
  // The same cache entry the tabs below read, so the counts cost nothing and follow the socket.
  // They count what the tab would show, so they follow "this pair" too.
  const orders = useQuery({ queryKey: openOrdersKey(account), queryFn: ({ signal }) => fetchOrders(signal, account), enabled: session.status === "signedIn" });
  const count = (kind: OpenKind) => orders.data
    ? ` (${orders.data.filter((o) => inKind(o, kind) && (!lock || o.symbol === lock)).length})` : "";
  const tabs: { id: Tab; label: string }[] = [
    { id: "open", label: t("orders.open") + count("book") }, { id: "triggers", label: t("orders.triggerOrders") + count("triggers") },
    { id: "history", label: t("orders.history") }, { id: "trades", label: t("orders.trades") }, { id: "assets", label: t("menu.assets") },
  ];
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-10 shrink-0 items-center gap-5 overflow-x-auto border-b border-border px-3 text-sm whitespace-nowrap">
        {tabs.map((x) => (
          <button key={x.id} type="button" role="tab" aria-selected={tab === x.id} onClick={() => setTab(x.id)}
                  className={`-mb-px h-10 border-b-2 ${tab === x.id ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>{x.label}</button>
        ))}
        <label className="ml-auto flex items-center gap-1.5 text-xs text-muted">
          <input type="checkbox" checked={thisPair} onChange={(e) => setThisPair(e.target.checked)} className="size-3.5 accent-current" />{t("trade.thisPair")}
        </label>
        <LocaleLink href={account ? "/copy/lead" : "/my/orders"} className="text-xs text-muted underline underline-offset-4 hover:text-text">{t("trade.viewMore")}</LocaleLink>
      </div>
      <div className="scroll-slim min-h-0 flex-1 overflow-auto px-1 py-1">
        {session.status !== "signedIn" ? (
          <p className="py-10 text-center text-sm text-muted">
            <LocaleLink href="/login" className="text-text underline underline-offset-4">{t("header.signIn")}</LocaleLink> {t("common.or")} <LocaleLink href="/register" className="text-text underline underline-offset-4">{t("header.createAccount")}</LocaleLink>
          </p>
        ) : tab === "open" ? <OpenOrders key={`book-${lock}-${account}`} kind="book" lockSymbol={lock} compact sub={account} />
          : tab === "triggers" ? <OpenOrders key={`triggers-${lock}-${account}`} kind="triggers" lockSymbol={lock} compact sub={account} />
            : tab === "history" ? <OrderHistory key={`history-${lock}-${account}`} lockSymbol={lock} compact sub={account} />
              : tab === "trades" ? <TradeHistory key={`trades-${lock}-${account}`} lockSymbol={lock} compact sub={account} />
                : <AssetsTable compact account={account} />}
      </div>
    </div>
  );
}
