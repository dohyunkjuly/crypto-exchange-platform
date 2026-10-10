"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { useSession } from "@/features/auth/session";
import { fetchSymbols, fetchTickers, type MarketSymbol, type Ticker } from "@/features/markets/api";
import { useFavorites } from "@/features/trade/favorites";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { percent, price } from "@/lib/format";
import { isNegative, isZero, scaleOf } from "@/lib/money";

const primary = "inline-flex h-12 items-center justify-center rounded-full bg-text px-7 text-base font-medium text-bg hover:opacity-85";
const secondary = "inline-flex h-12 items-center justify-center rounded-full border border-border px-7 text-base font-medium hover:border-border-strong";
const tone = (x: Ticker) => (isZero(x.lastPrice) || isZero(x.priceChangePercent) ? "text-muted" : isNegative(x.priceChangePercent) ? "text-down" : "text-up");

/**
 * The landing page: one idea a screen, a lot of air, and the only numbers on it live ones. The
 * hero's card is the markets as the app shows them, not a picture of them; there is no app to
 * photograph and nothing here is typed in.
 */
export function Home() {
  const t = useT();
  const session = useSession();
  const signedIn = session.status === "signedIn";
  const symbols = useQuery({ queryKey: ["symbols"], queryFn: ({ signal }) => fetchSymbols(signal), staleTime: 5 * 60_000 });
  const tickers = useQuery({ queryKey: ["tickers"], queryFn: ({ signal }) => fetchTickers(signal), refetchInterval: 5_000 });
  const markets = new Map((symbols.data ?? []).map((m) => [m.symbol, m]));
  // Busiest first is the API's order; a market that has never traded has no price to show.
  const live = (tickers.data ?? []).filter((x) => markets.has(x.symbol) && !isZero(x.lastPrice));
  const first = live[0];

  return (
    <div className="flex flex-col">
      <section className="grid items-center gap-12 py-20 lg:grid-cols-[1.2fr_1fr] lg:py-28">
        <div className="flex flex-col items-start gap-6">
          <h1 className="max-w-2xl text-4xl font-semibold tracking-tight sm:text-6xl">{t("home.headline")}</h1>
          <p className="max-w-lg text-lg text-muted">{t("home.tagline")}</p>
          <div className="flex flex-wrap gap-3 pt-2">
            {signedIn
              ? <LocaleLink href={first ? `/trade/${first.symbol}` : "/#markets"} className={primary}>{t("home.trade")}</LocaleLink>
              : <LocaleLink href="/register" className={primary}>{t("home.createAccount")}</LocaleLink>}
            <LocaleLink href="/#markets" className={secondary}>{t("home.markets")}</LocaleLink>
          </div>
        </div>
        <MarketsCard tickers={live} markets={markets} />
      </section>

      <section className="flex flex-col items-center gap-12 py-24">
        <h2 className="max-w-3xl text-center text-3xl font-semibold tracking-tight sm:text-5xl">{t("home.gateway")}</h2>
        <ul className="grid w-full gap-5 md:grid-cols-3">
          <Feature icon="trade" title={t("home.feature.trade")} body={t("home.feature.tradeBody")} href={first ? `/trade/${first.symbol}` : "/#markets"} action={t("home.feature.tradeAction")} />
          <Feature icon="copy" title={t("home.feature.copy")} body={t("home.feature.copyBody")} href="/copy" action={t("home.feature.copyAction")} />
          <Feature icon="api" title={t("home.feature.api")} body={t("home.feature.apiBody")} href="/my/account/api" action={t("home.feature.apiAction")} />
        </ul>
      </section>

      {live.length > 0 && (
        <section className="grid items-center gap-12 py-24 lg:grid-cols-2">
          <div className="flex flex-col items-start gap-6">
            <h2 className="text-3xl font-semibold tracking-tight sm:text-5xl">{t("home.portfolio")}</h2>
            <p className="max-w-md text-lg text-muted">{t("home.portfolioBody", { n: String(live.length) })}</p>
            <LocaleLink href={`/trade/${first.symbol}`} className={primary}>{t("home.trade")}</LocaleLink>
          </div>
          <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            {live.slice(0, 6).map((x) => {
              const m = markets.get(x.symbol)!;
              return (
                <li key={x.symbol}>
                  <LocaleLink href={`/trade/${x.symbol}`} aria-label={t("home.tradePair", { pair: `${m.baseAsset}/${m.quoteAsset}` })}
                              className="flex flex-col gap-4 rounded-xl border border-border p-5 transition-[border-color,box-shadow] hover:border-border-strong hover:shadow-lg hover:shadow-black/5">
                    <CoinIcon symbol={m.baseAsset} size={40} />
                    <span className="flex flex-col">
                      <span className="text-sm text-muted">{m.baseAsset}</span>
                      <span className="num text-base">{price(x.lastPrice, scaleOf(m.priceTick))} {m.quoteAsset}</span>
                    </span>
                    <span className={`num text-xl font-semibold ${tone(x)}`}>{percent(x.priceChangePercent)}</span>
                  </LocaleLink>
                </li>
              );
            })}
          </ul>
        </section>
      )}
    </div>
  );
}

/** The markets as the trade screen's picker shows them: a tab for the starred, a tab for all, five rows, "View more". */
function MarketsCard({ tickers, markets }: { tickers: Ticker[]; markets: Map<string, MarketSymbol> }) {
  const t = useT();
  const [favorites] = useFavorites();
  const [tab, setTab] = useState<"favorites" | "spot">("spot");
  const rows = (tab === "spot" ? tickers : tickers.filter((x) => favorites.has(x.symbol))).slice(0, 5);
  return (
    <div className="rounded-2xl border border-border bg-surface p-5 shadow-lg shadow-black/5">
      <div className="mb-4 flex items-center justify-between">
        <span className="text-base font-semibold">{t("home.markets")}</span>
        <div role="tablist" className="flex gap-1 rounded-full bg-surface-raised p-1 text-sm">
          {(["favorites", "spot"] as const).map((x) => (
            <button key={x} role="tab" aria-selected={tab === x} onClick={() => setTab(x)}
                    className={`rounded-full px-3 py-1 ${tab === x ? "bg-text text-bg" : "text-muted hover:text-text"}`}>
              {t(x === "spot" ? "trade.spot" : "trade.favorites")}
            </button>
          ))}
        </div>
      </div>
      <ul className="flex flex-col">
        {rows.map((x) => {
          const m = markets.get(x.symbol)!;
          return (
            <li key={x.symbol}>
              <LocaleLink href={`/trade/${x.symbol}`} className="-mx-2 flex items-center justify-between rounded-lg px-2 py-2.5 transition-colors hover:bg-surface-raised">
                <span className="flex items-center gap-3">
                  <CoinIcon symbol={m.baseAsset} size={28} />
                  <span className="text-base font-medium">{m.baseAsset}<span className="font-normal text-dim"> / {m.quoteAsset}</span></span>
                </span>
                <span className="flex flex-col items-end">
                  <span className="num text-base">{price(x.lastPrice, scaleOf(m.priceTick))}</span>
                  <span className={`num text-sm ${tone(x)}`}>{percent(x.priceChangePercent)}</span>
                </span>
              </LocaleLink>
            </li>
          );
        })}
        {rows.length === 0 && <li className="py-8 text-center text-base text-dim">{t(tab === "spot" ? "markets.empty" : "home.noFavorites")}</li>}
      </ul>
      <LocaleLink href="/#markets" className="mt-3 block text-center text-sm text-muted underline-offset-4 hover:text-text hover:underline">{t("home.viewMore")}</LocaleLink>
    </div>
  );
}

const ICONS = {
  trade: <><path d="M4 17l5-6 4 3 7-9" /><path d="M15 5h5v5" /></>,
  copy: <><circle cx="8" cy="8" r="3.5" /><circle cx="16" cy="16" r="3.5" /><path d="M11 9.5l2.5 3.5" /></>,
  api: <><path d="M8 7l-5 5 5 5" /><path d="M16 7l5 5-5 5" /><path d="M13.5 4l-3 16" /></>,
};

function Feature({ icon, title, body, href, action }: { icon: keyof typeof ICONS; title: string; body: string; href: string; action: string }) {
  return (
    <li className="flex flex-col items-center gap-5 rounded-2xl border border-border px-6 py-10 text-center">
      <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="text-muted">{ICONS[icon]}</svg>
      <h3 className="text-xl font-semibold">{title}</h3>
      <p className="max-w-xs text-base text-muted">{body}</p>
      <LocaleLink href={href} className="mt-auto inline-flex h-10 items-center rounded-full border border-border px-5 text-base font-medium hover:border-border-strong">{action}</LocaleLink>
    </li>
  );
}
