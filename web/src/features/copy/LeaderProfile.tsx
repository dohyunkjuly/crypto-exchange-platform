"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { useSession } from "@/features/auth/session";
import { fetchBalances } from "@/features/my/shared/api";
import { useLocale, useT } from "@/lib/i18n";
import { useNow } from "@/features/my/account/ApiKeys";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { localePath } from "@/lib/i18n/locales";
import { price } from "@/lib/format";
import { Dialog } from "@/components/ui/Dialog";
import { Empty } from "@/components/ui/Empty";
import { BackLink } from "@/features/my/shared/BackLink";
import { Pager, usePage } from "@/features/my/shared/Pager";
import { useFavoritesOf } from "@/lib/favorites";
import { fetchLeader, fetchLeaderTrades, follow, PER_PAGE, type CopyMode, type Leader, type LeaderProfile as Profile, type Terms } from "./api";
import { amount, card, PerformanceCard, ReturnChartCard, row } from "./Figures";

const control = "h-10 rounded-lg border border-border bg-surface-raised px-3 text-base text-text outline-none focus:border-border-strong";


/**
 * One leader: who they are, how the month went, what the account holds, and Copy. The figures
 * are the nightly job's and always public; the holdings and trades are the leader's to show.
 */
export function LeaderProfile({ id, startCopying = false }: { id: string; startCopying?: boolean }) {
  const t = useT();
  const locale = useLocale();
  const describe = useDescribe();
  const session = useSession();
  const now = useNow();
  const profile = useQuery({ queryKey: ["copy-leader", id], queryFn: ({ signal }) => fetchLeader(id, signal) });
  const [favorites, toggle] = useFavoritesOf("favorite-leaders");
  const [tab, setTab] = useState<"holdings" | "trades">("holdings");
  const [copying, setCopying] = useState(startCopying);

  if (profile.error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(profile.error)}</p>;
  if (!profile.data) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;

  const { leader, terms, portfolio } = profile.data;
  const s = leader.stats;
  const days = Math.max(0, Math.floor((now.getTime() - new Date(leader.since).getTime()) / 86_400_000));
  const starred = favorites.has(leader.id);
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  const signedIn = session.status === "signedIn";

  return (
    <div className="flex flex-col gap-8">
      <BackLink href="/copy" label={t("copy.leaders")} />

      <div className="flex flex-wrap items-start justify-between gap-6">
        <div className="flex items-start gap-4">
          <span aria-hidden className="flex size-16 shrink-0 items-center justify-center rounded-full bg-surface-raised text-2xl font-semibold">{leader.displayName.slice(0, 1).toUpperCase()}</span>
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-3">
              <h1 className="text-2xl font-semibold">{leader.displayName}</h1>
              <span className="rounded-full border border-border px-2.5 py-0.5 text-xs text-muted">{t(leader.portfolioShown ? "copy.public" : "copy.private")}</span>
            </div>
            {leader.bio && <p className="max-w-2xl text-base text-muted">{leader.bio}</p>}
            <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted">
              <div className="flex gap-1.5"><dt>{t("copy.daysLeading")}</dt><dd className="num text-text">{days.toLocaleString()}</dd></div>
              <div className="flex gap-1.5"><dt>{t("copy.followers")}</dt><dd className="num text-text">{leader.followers.toLocaleString()}/{terms.maxFollowers.toLocaleString()}</dd></div>
            </dl>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => toggle(leader.id)} aria-pressed={starred} aria-label={t(starred ? "copy.unfavorite" : "copy.favorite")}
                  className={`flex h-10 w-10 items-center justify-center rounded-lg border border-border text-lg ${starred ? "text-text" : "text-dim hover:text-text"}`}>{starred ? "★" : "☆"}</button>
          {!leader.accepting ? <span className="inline-flex h-10 items-center rounded-lg border border-border px-4 text-base text-dim">{t("copy.full")}</span>
            : session.status === "loading" ? null
            : signedIn ? <Button size="lg" onClick={() => setCopying(true)}>{t("copy.copyAction")}</Button>
            : <LocaleLink href="/login"><Button size="lg">{t("copy.signInToCopy")}</Button></LocaleLink>}
        </div>
      </div>

      {/* One row: the figures and the line. What the account holds and did is below, full width,
          so a long history grows the page instead of stretching the cards beside it. */}
      <div className="grid items-start gap-5 lg:grid-cols-[2fr_3fr]">
        <PerformanceCard stats={s}>
          {row(t("copy.leadingBalance"), amount(profile.data.leadingBalance, terms.quoteAsset))}
          {row(t("copy.fundsCopying"), amount(s?.fundsCopying, terms.quoteAsset))}
          {row(t("copy.profitShare"), `${leader.profitShareBps / 100}%`)}
        </PerformanceCard>
        <ReturnChartCard stats={s} empty="words" />
      </div>

      <section className={card}>
        <div role="tablist" className="flex gap-6">
          {(["holdings", "trades"] as const).map((x) => (
            <button key={x} role="tab" aria-selected={tab === x} onClick={() => setTab(x)}
                    className={`border-b-2 pb-2 text-base font-medium ${tab === x ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>
              {t(x === "holdings" ? "copy.holdings" : "copy.tradeHistory")}
            </button>
          ))}
        </div>
        {!portfolio ? <Empty>{t("copy.portfolioPrivate")}</Empty>
          : tab === "holdings" ? <Holdings portfolio={portfolio} quoteAsset={terms.quoteAsset} />
          : <Trades id={id} date={date} />}
      </section>

      {copying && (
        <Dialog title={t("copy.copyLeader", { name: leader.displayName })} onClose={() => setCopying(false)}>
          <FollowForm leader={leader} terms={terms} />
        </Dialog>
      )}
    </div>
  );
}

/** What the lead account holds, largest first, with each holding's share of the whole where it can be valued. */
function Holdings({ portfolio, quoteAsset }: { portfolio: NonNullable<Profile["portfolio"]>; quoteAsset: string }) {
  const t = useT();
  const total = portfolio.holdings.reduce((sum, h) => sum + Number(h.value ?? 0), 0);
  const page = usePage(portfolio.holdings, PER_PAGE);
  if (portfolio.holdings.length === 0) return <Empty>{t("copy.noHoldings")}</Empty>;
  return (
    <div className="flex flex-col gap-4">
    <table className="w-full text-base">
      <thead><tr className="border-b border-border text-left text-sm text-muted">
        <th className="py-2 font-medium">{t("copy.asset")}</th>
        <th className="py-2 text-right font-medium">{t("copy.amountCol")}</th>
        <th className="py-2 text-right font-medium">{t("copy.valueIn", { asset: quoteAsset })}</th>
        <th className="py-2 text-right font-medium">{t("copy.share")}</th>
      </tr></thead>
      <tbody>
        {page.rows.map((h) => (
          <tr key={h.asset} className="border-b border-border last:border-0">
            <td className="py-2.5 font-medium">{h.asset}</td>
            <td className="num py-2.5 text-right">{h.amount}</td>
            <td className="num py-2.5 text-right">{h.value === null ? "—" : Number(h.value).toLocaleString(undefined, { maximumFractionDigits: 2 })}</td>
            <td className="num py-2.5 text-right text-muted">{h.value === null || total === 0 ? "—" : `${(Number(h.value) / total * 100).toFixed(1)}%`}</td>
          </tr>
        ))}
      </tbody>
    </table>
    <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
    </div>
  );
}

/** The lead account's fills, a page at a time from the server, newest first. */
function Trades({ id, date }: { id: string; date: (iso: string) => string }) {
  const t = useT();
  const [page, setPage] = useState(0);
  const query = useQuery({ queryKey: ["copy-leader-trades", id, page], queryFn: ({ signal }) => fetchLeaderTrades(id, page, signal),
    placeholderData: (previous) => previous });
  const rows = query.data ?? [];
  if (query.isSuccess && rows.length === 0 && page === 0) return <Empty>{t("orders.emptyTrades")}</Empty>;
  return (
    <div className="flex flex-col gap-4">
      <div className="overflow-x-auto">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="py-2 font-medium">{t("copy.time")}</th>
            <th className="py-2 font-medium">{t("copy.market")}</th>
            <th className="py-2 font-medium">{t("copy.side")}</th>
            <th className="py-2 text-right font-medium">{t("orders.price")}</th>
            <th className="py-2 text-right font-medium">{t("orders.amount")}</th>
            <th className="py-2 text-right font-medium">{t("orders.total")}</th>
          </tr></thead>
          <tbody>
            {rows.map((x, i) => (
              <tr key={`${x.time}-${i}`} className="border-b border-border last:border-0">
                <td className="num py-2.5 text-muted">{date(x.time)}</td>
                <td className="py-2.5 font-medium">{x.symbol}</td>
                <td className={`py-2.5 ${x.side === "BUY" ? "text-up" : "text-down"}`}>{t(`side.${x.side}`)}</td>
                <td className="num py-2.5 text-right">{x.price}</td>
                <td className="num py-2.5 text-right">{x.quantity}</td>
                <td className="num py-2.5 text-right">{x.quoteQuantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pager current={page} hasNext={rows.length === PER_PAGE} onPage={setPage} />
    </div>
  );
}

/**
 * The follow's id is made once per form, so sending it twice makes one copy account. Amounts
 * are checked here against the mode's minimum and the balance, so the button says no before
 * the server has to.
 */
function FollowForm({ leader, terms }: { leader: Leader; terms: Terms }) {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const session = useSession();
  const signedIn = session.status === "signedIn";
  const balances = useQuery({ queryKey: ["balances"], queryFn: ({ signal }) => fetchBalances(signal), enabled: signedIn });
  const [mode, setMode] = useState<CopyMode>("FIXED_RATIO");
  const [amount, setAmount] = useState("");
  const [perOrder, setPerOrder] = useState("");
  // A percentage as typed; sent as basis points. The range is the server's (CopyFollow).
  const [slippage, setSlippage] = useState("0.5");
  const [followId] = useState(() => crypto.randomUUID());

  const start = useMutation({
    mutationFn: follow,
    onSuccess: (made) => {
      queryClient.invalidateQueries({ queryKey: ["balances"] });
      queryClient.invalidateQueries({ queryKey: ["copy-follows"] });
      router.replace(localePath(locale, `/copy/mine/${made.id}`));
    },
  });

  if (session.status === "loading") return null;
  if (!signedIn) {
    return <LocaleLink href="/login" className="self-start"><Button>{t("copy.signInToCopy")}</Button></LocaleLink>;
  }

  const available = balances.data?.find((b) => b.asset === terms.quoteAsset)?.available ?? "0";
  const minimum = mode === "FIXED_RATIO" ? terms.minFixedRatioAmount : terms.minFixedAmount;
  const tooMuch = amount !== "" && Number(amount) > Number(available);
  const tooLittle = amount !== "" && Number(amount) < Number(minimum);
  const fixed = mode === "FIXED_AMOUNT";
  const badPerOrder = fixed && (Number(perOrder) < Number(terms.minFixedAmount) || Number(perOrder) > Number(amount));
  const slippageBps = Math.round(Number(slippage) * 100);
  const badSlippage = slippage === "" || !(slippageBps >= 10 && slippageBps <= 500);
  const number = (set: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => set(e.target.value.replace(/[^\d.]/g, ""));

  return (
    <form className="flex flex-col gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            start.mutate({ followId, leaderId: leader.id, mode, amount, slippageCapBps: slippageBps, ...(fixed ? { amountPerOrder: perOrder } : {}) });
          }}>
      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1.5 text-sm font-medium text-muted">{t("copy.mode")}</legend>
        <div role="radiogroup" className="grid grid-cols-2 gap-2">
          {(["FIXED_RATIO", "FIXED_AMOUNT"] as const).map((m) => (
            <button key={m} type="button" role="radio" aria-checked={mode === m} onClick={() => setMode(m)}
                    className={`h-10 rounded-lg border px-3 text-base ${mode === m ? "border-border-strong bg-surface-raised text-text" : "border-border text-muted hover:text-text"}`}>
              {t(m === "FIXED_RATIO" ? "copy.fixedRatio" : "copy.fixedAmount")}
            </button>
          ))}
        </div>
        <span className="text-sm text-dim">{t(fixed ? "copy.fixedAmountHint" : "copy.fixedRatioHint")}</span>
      </fieldset>

      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("copy.amount", { asset: terms.quoteAsset })}</span>
        <span className="flex gap-2">
          <input className={`${control} num flex-1`} inputMode="decimal" pattern="\d*\.?\d*" required value={amount} onChange={number(setAmount)} />
          <Button type="button" variant="secondary" onClick={() => setAmount(available)}>{t("subAccounts.max")}</Button>
        </span>
        <span className={`num text-sm ${tooMuch || tooLittle ? "text-danger" : "text-dim"}`}>
          {tooLittle ? t("copy.minimum", { amount: minimum, asset: terms.quoteAsset })
            : t("copy.available", { amount: `${price(available, 2)} ${terms.quoteAsset}` })}
        </span>
      </label>

      {fixed && (
        <label className="flex flex-col gap-1.5">
          <span className="text-sm font-medium text-muted">{t("copy.amountPerOrder", { asset: terms.quoteAsset })}</span>
          <input className={`${control} num`} inputMode="decimal" pattern="\d*\.?\d*" required value={perOrder} onChange={number(setPerOrder)} />
          <span className={`num text-sm ${perOrder !== "" && badPerOrder ? "text-danger" : "text-dim"}`}>
            {t("copy.minimum", { amount: terms.minFixedAmount, asset: terms.quoteAsset })}
          </span>
        </label>
      )}

      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("copy.maxSlippage")}</span>
        <input className={`${control} num`} inputMode="decimal" pattern="\d*\.?\d*" required value={slippage} onChange={number(setSlippage)} />
        <span className={`num text-sm ${badSlippage ? "text-danger" : "text-dim"}`}>{t("copy.slippageRange")}</span>
      </label>

      <FormError error={start.error} />
      <Button type="submit" className="self-start" pending={start.isPending}
              disabled={Number(amount) <= 0 || tooMuch || tooLittle || badPerOrder || badSlippage}>
        {t("copy.start")}
      </Button>
    </form>
  );
}
