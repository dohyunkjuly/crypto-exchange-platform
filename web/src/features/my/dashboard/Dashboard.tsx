"use client";

import { useQueries } from "@tanstack/react-query";
import { fetchTickers } from "@/features/markets/api";
import { useT } from "@/lib/i18n";
import { price } from "@/lib/format";
import { AccountHeader } from "./AccountHeader";
import { Holdings } from "./Holdings";
import { fetchBalances, fetchFees } from "../shared/api";
import { estimateTotal, QUOTE } from "./estimate";

/** Who is signed in, what the account is worth, and what it holds. Everything else has a
 *  page of its own in the sidebar, and the header carries the account's standing. */
export function Dashboard() {
  const t = useT();
  const [balances, tickers, fees] = useQueries({
    queries: [
      { queryKey: ["balances"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchBalances(signal) },
      { queryKey: ["tickers"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchTickers(signal), refetchInterval: 5_000 },
      { queryKey: ["fees"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchFees(signal) },
    ],
  });
  const est = balances.data && tickers.data ? estimateTotal(balances.data, tickers.data) : null;

  return (
    <div className="flex flex-col gap-6">
      <h1 className="sr-only">{t("dashboard.title")}</h1>
      <AccountHeader />

      <div className="grid gap-4 md:grid-cols-2">
        <Tile label={t("dashboard.estimated")}>
          <div className="num text-2xl font-semibold">{est ? `${price(est.total, 2)} ${QUOTE}` : "—"}</div>
        </Tile>
        <Tile label={t("dashboard.feeTier")}>
          <div className="text-2xl font-semibold">{fees.data?.tierName ?? (fees.data ? "—" : "…")}</div>
          {fees.data && (
            <p className="num mt-1 text-sm text-muted">{t("dashboard.maker")} {fees.data.makerPercent}% · {t("dashboard.taker")} {fees.data.takerPercent}%</p>
          )}
        </Tile>
      </div>

      <Holdings />
    </div>
  );
}

function Tile({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-border p-5">
      <div className="text-sm text-muted">{label}</div>
      <div className="mt-2">{children}</div>
    </div>
  );
}
