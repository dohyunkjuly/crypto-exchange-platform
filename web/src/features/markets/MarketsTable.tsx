"use client";

import { useQueries } from "@tanstack/react-query";
import { useDescribe } from "@/components/ui/FormError";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { compact, percent, price } from "@/lib/format";
import { isNegative, isZero, scaleOf } from "@/lib/money";
import { fetchSymbols, fetchTickers, type MarketSymbol, type Ticker } from "./api";

/**
 * Tickers joined with symbol configuration: the ticker carries the numbers, the symbol says
 * how many decimals to show them at. Tickers poll; symbols are configuration and are not
 * re-asked until something invalidates them.
 */
export function MarketsTable({ limit }: { limit?: number }) {
  const t = useT();
  const describe = useDescribe();
  const [symbols, tickers] = useQueries({
    queries: [
      { queryKey: ["symbols"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchSymbols(signal), staleTime: 5 * 60_000 },
      { queryKey: ["tickers"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchTickers(signal), refetchInterval: 5_000 },
    ],
  });

  if (symbols.error || tickers.error) {
    return <p role="alert" className="py-8 text-center text-sm text-muted">{describe(symbols.error ?? tickers.error)}</p>;
  }
  if (!symbols.data || !tickers.data) {
    return <p className="py-8 text-center text-sm text-dim" aria-busy>{t("markets.loading")}</p>;
  }

  const bySymbol = new Map(symbols.data.map((s) => [s.symbol, s]));
  const rows = tickers.data.filter((t) => bySymbol.has(t.symbol)).slice(0, limit);

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-base">
        <thead>
          <tr className="border-b border-border text-left text-sm text-muted">
            <th className="py-2 pl-2 font-medium">{t("markets.market")}</th>
            <th className="py-2 text-right font-medium">{t("markets.lastPrice")}</th>
            <th className="py-2 text-right font-medium">{t("markets.change")}</th>
            <th className="hidden py-2 text-right font-medium sm:table-cell">{t("markets.high")}</th>
            <th className="hidden py-2 text-right font-medium sm:table-cell">{t("markets.low")}</th>
            <th className="py-2 pr-2 text-right font-medium">{t("markets.volume")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => <Row key={t.symbol} ticker={t} market={bySymbol.get(t.symbol)!} />)}
        </tbody>
      </table>
      {rows.length === 0 && <p className="py-8 text-center text-sm text-dim">{t("markets.empty")}</p>}
    </div>
  );
}

function Row({ ticker: t, market: m }: { ticker: Ticker; market: MarketSymbol }) {
  // A market that has never traded reports zeros. Zeros are not a price; show nothing rather
  // than a number a reader would try to interpret.
  const traded = !isZero(t.lastPrice);
  const down = isNegative(t.priceChangePercent);
  const flat = isZero(t.priceChangePercent);
  const tone = !traded || flat ? "text-muted" : down ? "text-down" : "text-up";
  // Display precision is the tick, not quoteDecimals: a market ticks in 0.01 while its quote
  // asset is shown to 6 or 8 places in a balance.
  const d = scaleOf(m.priceTick);
  const px = (v: string) => (traded ? price(v, d) : "\u2014");
  return (
    <tr className="border-b border-border transition-colors hover:bg-surface-raised">
      <td className="py-3 pl-2">
        <LocaleLink href={`/trade/${t.symbol}`} className="flex items-center gap-2">
          <CoinIcon symbol={m.baseAsset} size={24} />
          <span className="font-medium">{m.baseAsset}</span>
          <span className="text-dim">/ {m.quoteAsset}</span>
          {m.status !== "TRADING" && <span className="rounded bg-surface-raised px-1.5 py-0.5 text-[10px] text-muted">{m.status.replace("_", " ")}</span>}
        </LocaleLink>
      </td>
      <td className={`num py-3 text-right ${tone}`}>{px(t.lastPrice)}</td>
      <td className={`num py-3 text-right ${tone}`}>{traded ? percent(t.priceChangePercent) : "\u2014"}</td>
      <td className="num hidden py-3 text-right text-muted sm:table-cell">{px(t.highPrice)}</td>
      <td className="num hidden py-3 text-right text-muted sm:table-cell">{px(t.lowPrice)}</td>
      <td className="num py-3 pr-2 text-right text-muted">{traded ? `${compact(t.quoteVolume)} ${m.quoteAsset}` : "\u2014"}</td>
    </tr>
  );
}
