"use client";

import { useQueries } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useDescribe } from "@/components/ui/FormError";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { fetchTickers } from "@/features/markets/api";
import { useT } from "@/lib/i18n";
import { price } from "@/lib/format";
import { balancesKey, fetchAssets, fetchBalances } from "../shared/api";
import { lastPrices, QUOTE, valueOf } from "../dashboard/estimate";

/** `compact`: no title, denser rows -- the trading screen's Assets tab. */
export function AssetsTable({ compact = false, account }: { compact?: boolean; account?: "lead" } = {}) {
  const t = useT();
  const describe = useDescribe();
  const [balances, assets, tickers] = useQueries({
    queries: [
      { queryKey: balancesKey(account), queryFn: ({ signal }: { signal: AbortSignal }) => fetchBalances(signal, account) },
      { queryKey: ["assets"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchAssets(signal), staleTime: 5 * 60_000 },
      { queryKey: ["tickers"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchTickers(signal), refetchInterval: 5_000 },
    ],
  });
  const error = balances.error ?? assets.error;
  if (error) return <p role="alert" className={`py-8 text-center text-muted ${compact ? "text-xs" : "text-base"}`}>{describe(error)}</p>;
  if (!balances.data || !assets.data) return <p className={`py-8 text-center text-dim ${compact ? "text-xs" : "text-base"}`} aria-busy>{t("common.loading")}</p>;

  const decimals = new Map(assets.data.map((a) => [a.symbol, a.displayDecimals]));
  const last = lastPrices(tickers.data ?? []);

  const rows = balances.data.map((b) => {
    const d = decimals.get(b.asset) ?? 8; const v = valueOf(b.asset, b.total, last);
    return (
      <tr key={b.asset} className="border-b border-border transition-colors hover:bg-surface-raised">
        <td className={`${compact ? "py-1.5" : "py-3"} pl-2 font-medium`}><span className="flex items-center gap-2"><CoinIcon symbol={b.asset} size={compact ? 20 : 24} />{b.asset}</span></td>
        <td className={`num ${compact ? "py-1.5" : "py-3"} text-right`}>{price(b.available, d)}</td>
        <td className={`num ${compact ? "py-1.5" : "py-3"} text-right text-muted`}>{price(b.locked, d)}</td>
        <td className={`num ${compact ? "py-1.5" : "py-3"} text-right`}>{price(b.total, d)}</td>
        <td className={`num ${compact ? "py-1.5" : "py-3"} pr-2 text-right text-muted`}>{v ? `${price(v, 2)} ${QUOTE}` : "\u2014"}</td>
      </tr>
    );
  });
  const table = (
    <table className={`w-full ${compact ? "text-xs" : "text-base"}`}>
      {/* Sticky, like the dashboard's holdings: the border rides on each th, since a collapsed
          table border on a sticky element renders under the rows passing beneath it. */}
      <thead className={compact ? "" : "sticky top-0 z-10 bg-bg"}><tr className={`text-left text-muted ${compact ? "border-b border-border text-xs" : "text-sm [&>th]:border-b [&>th]:border-border"}`}>
        <th className="py-2 pl-2 font-medium">{t("assets.asset")}</th>
        <th className="py-2 text-right font-medium">{t("assets.available")}</th>
        <th className="py-2 text-right font-medium">{t("assets.inOrder")}</th>
        <th className="py-2 text-right font-medium">{t("assets.total")}</th>
        <th className="py-2 pr-2 text-right font-medium">{t("assets.estValue")}</th>
      </tr></thead>
      <tbody>{rows}</tbody>
    </table>
  );

  if (compact) {
    return (
      <div className="overflow-x-auto">
        {table}
        {balances.data.length === 0 && <Empty compact>{t("assets.empty")}</Empty>}
      </div>
    );
  }
  // The same box as the dashboard's holdings, taller: this page is the list, so it gets the
  // screen, and the list scrolls inside it whether it holds one asset or forty -- or none.
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border p-5">
      <h1 className="text-base font-semibold">{t("assets.title")}</h1>
      <div className="scroll-slim max-h-[calc(100vh-14rem)] min-h-[32rem] overflow-auto">
        {table}
        {balances.data.length === 0 && <Empty>{t("assets.empty")}</Empty>}
      </div>
    </section>
  );
}
