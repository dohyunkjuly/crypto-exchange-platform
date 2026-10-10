"use client";

import { useQueries } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { useDescribe } from "@/components/ui/FormError";
import { fetchTickers } from "@/features/markets/api";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { localePath } from "@/lib/i18n/locales";
import { price } from "@/lib/format";
import { add, compare } from "@/lib/money";
import { fetchAllSubAccountBalances, fetchAssets, fetchSubAccounts, type Asset, type Balance, type SubAccount } from "../shared/api";
import { BackLink } from "../shared/BackLink";
import { Pager, usePage } from "../shared/Pager";
import { lastPrices, QUOTE, valueOf } from "../dashboard/estimate";
import { matches, NO_FILTER, SubAccountFilters, type SubFilter } from "./SubAccountFilters";

const PER_PAGE = 10;

/**
 * What each sub holds. The list is one row per sub with its balance in {@link QUOTE}; Details
 * (`?sub=`) opens that sub's coins. Every sub's balances arrive in one request, so opening a
 * sub costs nothing and the totals cannot disagree between the two views.
 *
 * The master's own balances are the assets page, not this one, and nothing here deposits:
 * this exchange touches no blockchain (docs/reference/white-label.md).
 */
export function SubAssets() {
  const t = useT();
  const describe = useDescribe();
  const router = useRouter();
  const locale = useLocale();
  const params = useSearchParams();
  const [applied, setApplied] = useState<SubFilter>(NO_FILTER);

  const [subs, balances, assets, tickers] = useQueries({
    queries: [
      { queryKey: ["sub-accounts"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchSubAccounts(signal) },
      { queryKey: ["sub-accounts", "balances"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchAllSubAccountBalances(signal) },
      { queryKey: ["assets"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchAssets(signal), staleTime: 5 * 60_000 },
      { queryKey: ["tickers"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchTickers(signal), refetchInterval: 5_000 },
    ],
  });

  const all = subs.data ?? [];
  const held = (sub: SubAccount) => balances.data?.[String(sub.id)] ?? [];
  const page = usePage(all.filter((s) => matches(s, applied)), PER_PAGE);
  const error = subs.error ?? balances.error;

  const wanted = Number(params.get("sub") ?? "");
  const chosen = all.find((s) => s.id === wanted);
  const back = () => router.replace(localePath(locale, "/my/sub-accounts/assets"));

  if (error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(error)}</p>;
  if (subs.isPending || balances.isPending) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;

  if (chosen) {
    return (
      <div className="flex flex-col gap-6">
        <BackLink href="/my/sub-accounts/assets" label={t("subAccounts.backToAssets")} />
        <Coins sub={chosen} balances={held(chosen)} assets={assets.data ?? []} tickers={tickers.data ?? []} />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{t("menu.subAssets")}</h1>
        <LocaleLink href="/my/sub-accounts/transfer" className="inline-flex h-10 items-center rounded-lg border border-border px-4 text-base font-medium hover:border-border-strong">
          {t("subAccounts.transfer")}
        </LocaleLink>
      </div>

      <SubAccountFilters subs={all} onApply={setApplied}>
        <span className="text-base text-muted">{t("subAccounts.count", { n: all.length })}</span>
      </SubAccountFilters>

      <Totals subs={page.rows} balances={held} tickers={tickers.data ?? []}
              empty={t(all.length === 0 ? "subAccounts.empty" : "subAccounts.noMatch")}
              onDetails={(s) => router.replace(localePath(locale, `/my/sub-accounts/assets?sub=${s.id}`))} />
      <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
      {/* A sub that vanished while its details were open: say so rather than render nothing. */}
      {wanted > 0 && !chosen && <p className="text-center text-base text-dim">{t("subAccounts.noMatch")} <button onClick={back} className="underline underline-offset-4">{t("subAccounts.backToAssets")}</button></p>}
    </div>
  );
}

/** One row per sub, worth what it holds priced in {@link QUOTE}. */
function Totals({ subs, balances, tickers, empty, onDetails }: {
  subs: SubAccount[]; balances: (sub: SubAccount) => Balance[]; tickers: Parameters<typeof lastPrices>[0];
  empty: string; onDetails: (sub: SubAccount) => void;
}) {
  const t = useT();
  const last = lastPrices(tickers);
  const total = (sub: SubAccount) => balances(sub)
    .reduce((sum, b) => { const v = valueOf(b.asset, b.total, last); return v ? add(sum, v) : sum; }, "0");

  return (
    <div className="flex flex-col gap-3">
      {/* Cards on a phone: six columns of an account row do not survive a narrow screen. */}
      <ul className="flex flex-col gap-3 sm:hidden">
        {subs.map((s) => (
          <li key={s.id} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <div className="flex items-center justify-between gap-3">
              <span className="text-base font-medium">{s.label}</span>
              <span className={`rounded-full border border-border px-2 py-0.5 text-xs ${s.frozen ? "text-danger" : "text-up"}`}>
                {t(s.frozen ? "subAccounts.frozen" : "subAccounts.active")}
              </span>
            </div>
            <span className="break-all text-sm text-muted">{s.email}</span>
            <span className="num text-sm">{price(total(s), 2)} {QUOTE}</span>
            <div className="mt-1 flex items-center gap-4 border-t border-border pt-3 text-base">
              <TransferLinks sub={s} />
              <button onClick={() => onDetails(s)} className="text-text underline-offset-4 hover:underline">{t("subAccounts.details")}</button>
            </div>
          </li>
        ))}
      </ul>

      <div className="hidden overflow-x-auto rounded-lg border border-border sm:block">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="px-4 py-3 font-medium">{t("subAccounts.label")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.email")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.status")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("subAccounts.totalBalance")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.transfer")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("subAccounts.actions")}</th>
          </tr></thead>
          <tbody>
            {subs.map((s) => (
              <tr key={s.id} className="border-b border-border transition-colors last:border-0 hover:bg-surface-raised">
                <td className="px-4 py-3 font-medium">{s.label}</td>
                <td className="px-4 py-3 text-muted">{s.email}</td>
                <td className={`px-4 py-3 ${s.frozen ? "text-danger" : "text-up"}`}>{t(s.frozen ? "subAccounts.frozen" : "subAccounts.active")}</td>
                <td className="num px-4 py-3 text-right">{price(total(s), 2)} {QUOTE}</td>
                <td className="px-4 py-3"><span className="flex items-center gap-4"><TransferLinks sub={s} /></span></td>
                <td className="px-4 py-3 text-right">
                  <button onClick={() => onDetails(s)} className="text-text underline-offset-4 hover:underline">{t("subAccounts.details")}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {subs.length === 0 && <Empty className="rounded-lg border border-border sm:-mt-3 sm:rounded-t-none sm:border-t-0">{empty}</Empty>}
    </div>
  );
}

/** Nothing may reach or leave a frozen sub, so it is offered neither direction. */
function TransferLinks({ sub }: { sub: SubAccount }) {
  const t = useT();
  if (sub.frozen) {
    return <span className="text-dim">—</span>;
  }
  return (
    <>
      <LocaleLink href={`/my/sub-accounts/transfer?to=${sub.id}`} className="text-text underline-offset-4 hover:underline">
        {t("subAccounts.transferIn")}
      </LocaleLink>
      <LocaleLink href={`/my/sub-accounts/transfer?from=${sub.id}`} className="text-text underline-offset-4 hover:underline">
        {t("subAccounts.transferOut")}
      </LocaleLink>
    </>
  );
}

/** One sub's coins, most valuable first. */
function Coins({ sub, balances, assets, tickers }: {
  sub: SubAccount; balances: Balance[]; assets: Asset[]; tickers: Parameters<typeof lastPrices>[0];
}) {
  const t = useT();
  const last = lastPrices(tickers);
  const meta = new Map(assets.map((a) => [a.symbol, a]));
  const rows = balances
    .map((b) => ({ ...b, value: valueOf(b.asset, b.total, last), decimals: meta.get(b.asset)?.displayDecimals ?? 8, name: meta.get(b.asset)?.name ?? b.asset }))
    // Priced first, biggest holding at the top; the rest keep a stable alphabetical order.
    .sort((a, b) => (a.value && b.value ? compare(b.value, a.value) : a.value ? -1 : b.value ? 1 : a.asset.localeCompare(b.asset)));
  const page = usePage(rows, PER_PAGE);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">{sub.label}</h1>
        <span className="flex items-center gap-4 text-base"><TransferLinks sub={sub} /></span>
      </div>

      <ul className="flex flex-col gap-3 sm:hidden">
        {page.rows.map((r) => (
          <li key={r.asset} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <span className="flex items-center gap-2 text-base font-medium"><CoinIcon symbol={r.asset} size={24} />{r.asset}
              <span className="text-sm font-normal text-dim">{r.name}</span></span>
            <Line label={t("assets.available")} value={`${price(r.available, r.decimals)}`} />
            <Line label={t("assets.inOrder")} value={`${price(r.locked, r.decimals)}`} />
            <Line label={t("assets.total")} value={`${price(r.total, r.decimals)}`} />
            <Line label={t("assets.estValue")} value={r.value ? `${price(r.value, 2)} ${QUOTE}` : "—"} />
          </li>
        ))}
      </ul>

      <div className="hidden overflow-x-auto rounded-lg border border-border sm:block">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="px-4 py-3 font-medium">{t("assets.asset")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.coinName")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("assets.total")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("assets.available")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("assets.inOrder")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("assets.estValue")}</th>
          </tr></thead>
          <tbody>
            {page.rows.map((r) => (
              <tr key={r.asset} className="border-b border-border transition-colors last:border-0 hover:bg-surface-raised">
                <td className="px-4 py-3 font-medium"><span className="flex items-center gap-2"><CoinIcon symbol={r.asset} size={24} />{r.asset}</span></td>
                <td className="px-4 py-3 text-muted">{r.name}</td>
                <td className="num px-4 py-3 text-right">{price(r.total, r.decimals)}</td>
                <td className="num px-4 py-3 text-right">{price(r.available, r.decimals)}</td>
                <td className="num px-4 py-3 text-right text-muted">{price(r.locked, r.decimals)}</td>
                <td className="num px-4 py-3 text-right text-muted">{r.value ? `${price(r.value, 2)} ${QUOTE}` : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length === 0 && <Empty className="rounded-lg border border-border sm:-mt-3 sm:rounded-t-none sm:border-t-0">{t("subAccounts.noBalances")}</Empty>}
      <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
    </div>
  );
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <span className="flex items-center justify-between gap-3 text-sm">
      <span className="text-muted">{label}</span>
      <span className="num">{value}</span>
    </span>
  );
}
