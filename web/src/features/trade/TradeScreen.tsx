"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useDescribe } from "@/components/ui/FormError";
import { fetchLead } from "@/features/copy/api";
import { fetchSymbols } from "@/features/markets/api";
import { useT } from "@/lib/i18n";
import { AccountPanel } from "./AccountPanel";
import { AssetsPanel } from "./AssetsPanel";
import { Chart } from "./Chart";
import { MarketStream } from "./MarketStream";
import { MarketStrip } from "./MarketStrip";
import { OrderBook } from "./OrderBook";
import { TradeForm } from "./TradeForm";
import { useSession } from "@/features/auth/session";
import { UserStream } from "@/features/my/shared/UserStream";
import { TradeLayout } from "./TradeLayout";
import { useTradingAccount } from "./tradingAccount";

/** The trading screen for one market. The panels arrive one by one; each region is a placeholder until then. */
export function TradeScreen({ symbol }: { symbol: string }) {
  const t = useT();
  const describe = useDescribe();
  // Refetched every few seconds only while a market is paused: that is the one time the listing changes on its own.
  const symbols = useQuery({ queryKey: ["symbols"], queryFn: ({ signal }) => fetchSymbols(signal), staleTime: 5 * 60_000,
    refetchInterval: (q) => q.state.data?.some((m) => m.paused) ? 5_000 : false });
  const session = useSession();
  const signedIn = session.status === "signedIn";
  // A leader trades the lead account from here too; everyone else never sees the switch.
  const lead = useQuery({ queryKey: ["copy-lead"], queryFn: ({ signal }) => fetchLead(signal), enabled: signedIn, staleTime: 60_000 });
  const [chosen, choose] = useTradingAccount();
  const canLead = signedIn && !!lead.data?.lead?.hasAccount;
  const account = canLead ? chosen : undefined;
  const [picked, setPicked] = useState<string | null>(null);
  const [best, setBest] = useState<{ bid: string | null; ask: string | null }>({ bid: null, ask: null });
  const market = symbols.data?.find((m) => m.symbol === symbol.toUpperCase());

  if (symbols.error) return <p role="alert" className="p-8 text-center text-sm text-muted">{describe(symbols.error)}</p>;
  if (!symbols.data) return <p className="p-8 text-center text-sm text-dim" aria-busy>{t("common.loading")}</p>;
  if (!market) return <p role="alert" className="p-8 text-center text-sm text-muted">{t("trade.unknownMarket", { symbol: symbol.toUpperCase() })}</p>;

  const screen = (
    <MarketStream>
      <TradeLayout
        strip={<MarketStrip market={market} />}
        chart={<Chart market={market} />}
        book={(only) => <OrderBook market={market} only={only} onPickPrice={setPicked} onBest={setBest} />}
        form={<>
          <TradeForm market={market} pickedPrice={picked} bestBid={best.bid} bestAsk={best.ask} account={account} onAccount={canLead ? choose : undefined} />
          <AssetsPanel market={market} account={account} />
        </>}
        panel={<AccountPanel market={market} account={account} />}
      />
    </MarketStream>
  );
  // The private stream only for a signed-in viewer: it would just be refused otherwise.
  return signedIn ? <UserStream account={account}>{screen}</UserStream> : screen;
}
