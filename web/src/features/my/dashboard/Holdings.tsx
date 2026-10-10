"use client";

import { useQueries } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { useDescribe } from "@/components/ui/FormError";
import { fetchTickers } from "@/features/markets/api";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { percent, price } from "@/lib/format";
import { isNegative, isZero } from "@/lib/money";
import { fetchAssets, fetchBalances } from "../shared/api";
import { holdings, QUOTE, type Holding } from "./estimate";

/**
 * Everything the account holds, biggest first, with the market price beside it and a way to
 * act on it: the dashboard's answer to "what do I have, and what is it doing". Scrolled
 * rather than cut short, so a long list is still all there.
 */
export function Holdings() {
  const t = useT();
  const describe = useDescribe();
  const [balances, assets, tickers] = useQueries({
    queries: [
      { queryKey: ["balances"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchBalances(signal) },
      { queryKey: ["assets"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchAssets(signal), staleTime: 5 * 60_000 },
      { queryKey: ["tickers"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchTickers(signal), refetchInterval: 5_000 },
    ],
  });
  const error = balances.error ?? assets.error;
  const rows = balances.data && assets.data
    ? holdings(balances.data, assets.data, tickers.data ?? []) : null;

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border p-5">
      <h2 className="text-base font-semibold">{t("dashboard.holdings")}</h2>

      {error ? <p role="alert" className="py-6 text-center text-base text-muted">{describe(error)}</p>
        : !rows ? <p className="py-6 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>
        : (
          <div className="scroll-slim max-h-96 overflow-auto">
            <table className="w-full text-base">
              {/* The border rides on each th, not the row: a collapsed table border on a
                  sticky element renders under the rows passing beneath it. */}
              <thead className="sticky top-0 z-10 bg-bg">
              <tr className="text-left text-sm text-muted [&>th]:border-b [&>th]:border-border">
                <th className="py-2 pl-2 font-medium">{t("assets.asset")}</th>
                <th className="py-2 text-right font-medium">{t("assets.total")}</th>
                <th className="hidden py-2 text-right font-medium sm:table-cell">{t("markets.lastPrice")}</th>
                <th className="py-2 text-right font-medium">{t("markets.change")}</th>
                <th className="py-2 pr-2 text-right font-medium" />
              </tr></thead>
              <tbody>{rows.map((h) => <Row key={h.asset} holding={h} />)}</tbody>
            </table>
            {/* The columns stay, empty or not: they say what the account would hold. */}
            {rows.length === 0 && (
              <Empty>
                {t("dashboard.noHoldings")}{" "}
                <LocaleLink href="/" className="text-text underline underline-offset-4">{t("dashboard.explore")}</LocaleLink>
              </Empty>
            )}
          </div>
        )}
    </section>
  );
}

function Row({ holding: h }: { holding: Holding }) {
  const t = useT();
  const tone = !h.changePercent || isZero(h.changePercent) ? "text-muted"
    : isNegative(h.changePercent) ? "text-down" : "text-up";
  return (
    <tr className="border-b border-border transition-colors hover:bg-surface-raised">
      <td className="py-3 pl-2">
        <span className="flex items-center gap-2">
          <CoinIcon symbol={h.asset} size={24} />
          <span className="font-medium">{h.asset}</span>
          <span className="hidden text-sm text-dim sm:inline">{h.name}</span>
        </span>
      </td>
      <td className="num py-3 text-right">
        <div>{price(h.total, h.decimals)}</div>
        {/* The amount is what they own; the value is what it is worth today. Both, stacked. */}
        {h.value && <div className="text-sm text-muted">≈ {price(h.value, 2)} {QUOTE}</div>}
      </td>
      <td className="num hidden py-3 text-right sm:table-cell">{h.price ? price(h.price, 2) : "—"}</td>
      <td className={`num py-3 text-right ${tone}`}>{h.changePercent ? percent(h.changePercent) : "—"}</td>
      <td className="py-3 pr-2 text-right">
        {h.symbol && (
          <LocaleLink href={`/trade/${h.symbol}`} className="text-text underline-offset-4 hover:underline">
            {t("dashboard.trade")}
          </LocaleLink>
        )}
      </td>
    </tr>
  );
}
