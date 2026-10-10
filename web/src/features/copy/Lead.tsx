"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Dialog } from "@/components/ui/Dialog";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { fetchSymbols } from "@/features/markets/api";
import { fetchBalances } from "@/features/my/shared/api";
import { SelectFilter } from "@/features/my/shared/filters";
import { setTradingAccount } from "@/features/trade/tradingAccount";
import { BackLink } from "@/features/my/shared/BackLink";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { amount, card, PerformanceCard, ReturnChartCard, row } from "./Figures";
import { LeadKeys } from "./LeadKeys";
import { applyToLead, fetchLead, fetchLeader, fundLead, setPortfolioShown, stopLeading, withdrawLead, type LeadPage, type LeadState, type MyLead } from "./api";

const control = "h-10 rounded-lg border border-border bg-surface-raised px-3 text-base text-text outline-none focus:border-border-strong";
const tone = (s: LeadState) => (s === "ACTIVE" ? "text-up" : "text-muted");

/**
 * The customer as a leader, one state at a time. Nothing yet: the form. Leading: the console -- the figures a follower sees, the holdings and the
 * money in and out, the keys, the settings. Closed or stopped by staff: a status, what is left
 * to take out, and a way to start again. It shows a state, never a reason: why staff decided
 * something is not the customer's to be told here.
 */
export function Lead() {
  const t = useT();
  const describe = useDescribe();
  const queryClient = useQueryClient();
  const page = useQuery({ queryKey: ["copy-lead"], queryFn: ({ signal }) => fetchLead(signal) });
  const [applying, setApplying] = useState(false);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["copy-lead"] });
    queryClient.invalidateQueries({ queryKey: ["balances"] });
    setApplying(false);
  };

  if (page.error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(page.error)}</p>;
  if (!page.data) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;

  const { lead } = page.data;
  const body = !lead
    ? <Start page={page.data} onDone={refresh} />
    : lead.status === "ACTIVE"
      ? <Console lead={lead} page={page.data} onChange={refresh} />
      : <Ended lead={lead} page={page.data} onChange={refresh} applying={applying} onApply={() => setApplying(true)} onClose={() => setApplying(false)} />;

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/copy" label={t("copy.leaders")} />
      {body}
    </div>
  );
}

/** Nobody leading yet: the form, and only the form. */
function Start({ page, onDone }: { page: LeadPage; onDone: () => void }) {
  const t = useT();
  return (
    // The one page here that is a form: the form sits at a reading width, the page at the section's.
    <div className="mx-auto flex w-full max-w-lg flex-col gap-6">
      <h1 className="text-xl font-semibold">{t("copy.beLeader")}</h1>
      <Apply lead={null} page={page} onDone={onDone} />
    </div>
  );
}

type Tab = "overview" | "keys" | "settings";

/** Leading: what a follower sees, then the account itself, the keys and the settings, each on its tab. */
function Console({ lead, page, onChange }: { lead: MyLead; page: LeadPage; onChange: () => void }) {
  const t = useT();
  const [tab, setTab] = useState<Tab>("overview");
  const symbols = useQuery({ queryKey: ["symbols"], queryFn: ({ signal }) => fetchSymbols(signal), staleTime: 5 * 60_000 });
  // The public profile, so the leader sees exactly what a follower sees.
  const profile = useQuery({ queryKey: ["copy-leader", lead.id], queryFn: ({ signal }) => fetchLeader(lead.id, signal) });
  const [moving, setMoving] = useState<"in" | "out" | null>(null);
  // A lead account trades the copy asset's markets only, so the way in is one of those.
  const market = symbols.data?.find((m) => m.quoteAsset === page.quoteAsset);
  const stats = profile.data?.leader.stats ?? null;
  const tabs: { id: Tab; label: string }[] = [
    { id: "overview", label: t("copy.overview") }, { id: "keys", label: t("menu.api") }, { id: "settings", label: t("copy.settings") },
  ];
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">{lead.displayName}</h1>
          <p className="text-base">
            <span className={tone(lead.status)}>{t(`leadStatus.${lead.status}`)}</span>
            <span className="text-muted"> · {t("copy.followers")} {lead.followers.toLocaleString()}</span>
            <span className="text-muted"> · {t("copy.earned", { amount: `${lead.earned} ${page.quoteAsset}` })}</span>
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {market && (
            <LocaleLink href={`/trade/${market.symbol}`} onClick={() => setTradingAccount("lead")}
                        className="inline-flex h-10 items-center rounded-lg bg-text px-4 text-base font-medium text-bg hover:opacity-85">
              {t("copy.tradeLead")}
            </LocaleLink>
          )}
          <Button variant="secondary" onClick={() => setMoving("in")}>{t("copy.addFunds")}</Button>
          <Button variant="secondary" onClick={() => setMoving("out")}>{t("copy.takeOut")}</Button>
        </div>
      </div>
      {moving && <LeadFunds page={page} lead={lead} direction={moving} onClose={() => setMoving(null)} onDone={onChange} />}

      <div role="tablist" className="flex gap-6 border-b border-border">
        {tabs.map((x) => (
          <button key={x.id} role="tab" aria-selected={tab === x.id} onClick={() => setTab(x.id)}
                  className={`-mb-px border-b-2 pb-2 text-base font-medium ${tab === x.id ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>
            {x.label}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <>
          {/* Laid out as the public profile: the figures beside the line, the holdings full width below. */}
          <div className="grid items-start gap-5 lg:grid-cols-[2fr_3fr]">
            <PerformanceCard stats={stats}>
              {row(t("copy.leadingBalance"), amount(profile.data?.leadingBalance, page.quoteAsset))}
              {row(t("copy.fundsCopying"), amount(stats?.fundsCopying, page.quoteAsset))}
              {row(t("copy.earnedTotal"), amount(lead.earned, page.quoteAsset))}
              {row(t("copy.profitShare"), profile.data ? `${profile.data.leader.profitShareBps / 100}%` : "—")}
            </PerformanceCard>
            <ReturnChartCard stats={stats} />
          </div>
          <section className={card}>
            <h2 className="text-lg font-semibold">{t("copy.holdings")}</h2>
            <Holdings lead={lead} page={page} />
          </section>
        </>
      )}
      {tab === "keys" && <LeadKeys />}
      {tab === "settings" && <Settings lead={lead} onChange={onChange} />}
    </>
  );
}

function Holdings({ lead, page }: { lead: MyLead; page: LeadPage }) {
  const t = useT();
  if (lead.holdings.length === 0) return <Empty>{t("copy.noHoldings")}</Empty>;
  return (
    <>
      <table className="w-full text-base">
        <thead><tr className="border-b border-border text-left text-sm text-muted">
          <th className="py-2 font-medium">{t("copy.asset")}</th>
          <th className="py-2 text-right font-medium">{t("copy.availableCol")}</th>
          <th className="py-2 text-right font-medium">{t("copy.inOrders")}</th>
        </tr></thead>
        <tbody>
          {lead.holdings.map((h) => (
            <tr key={h.asset} className="border-b border-border last:border-0">
              <td className="py-2.5 font-medium">{h.asset}</td>
              <td className="num py-2.5 text-right">{h.available}</td>
              <td className="num py-2.5 text-right text-muted">{h.locked}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {lead.status === "ACTIVE" && <p className="text-sm text-dim">{t("copy.leadMinimum", { amount: page.minBalance, asset: page.quoteAsset })}</p>}
    </>
  );
}

/** What the leader can change about leading, and the way out, last. */
function Settings({ lead, onChange }: { lead: MyLead; onChange: () => void }) {
  const t = useT();
  const [stopping, setStopping] = useState(false);
  const show = useMutation({ mutationFn: setPortfolioShown, onSettled: onChange });
  const stop = useMutation({ mutationFn: stopLeading, onSettled: () => { setStopping(false); onChange(); } });
  return (
    <div className="flex flex-col gap-6">
      <section className={card}>
        <label className="flex items-start gap-3 text-base">
          <input type="checkbox" className="mt-1 size-4 accent-current" checked={lead.portfolioShown} disabled={show.isPending}
                 onChange={(e) => show.mutate(e.target.checked)} />
          <span className="flex flex-col gap-0.5">
            <span>{t("copy.showPortfolio")}</span>
            <span className="text-sm text-dim">{t("copy.showPortfolioHint")}</span>
          </span>
        </label>
        <FormError error={show.error} />
      </section>
      <section className={`${card} border-danger/40`}>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-0.5">
            <span className="text-base font-medium">{t("copy.stopLeading")}</span>
            <span className="max-w-xl text-sm text-dim">{t("copy.stopLeadingConfirm")}</span>
          </div>
          <Button variant="secondary" onClick={() => setStopping(true)}>{t("copy.stopLeading")}</Button>
        </div>
        <FormError error={stop.error} />
      </section>
      {stopping && (
        <ConfirmDialog title={t("copy.stopLeading")} message={t("copy.stopLeadingConfirm")} pending={stop.isPending}
                       onConfirm={() => stop.mutate()} onClose={() => setStopping(false)} />
      )}
    </div>
  );
}

/** Closed by the leader, or no longer available: the state, what is left to take out, and the way back in. */
function Ended({ lead, page, onChange, applying, onApply, onClose }: {
  lead: MyLead; page: LeadPage; onChange: () => void; applying: boolean; onApply: () => void; onClose: () => void;
}) {
  const t = useT();
  const [moving, setMoving] = useState(false);
  const holdsSomething = lead.holdings.some((h) => Number(h.available) > 0);
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">{lead.displayName}</h1>
          <p className="text-base">
            <span className={tone(lead.status)}>{t(`leadStatus.${lead.status}`)}</span>
            <span className="text-muted"> · {t("copy.earned", { amount: `${lead.earned} ${page.quoteAsset}` })}</span>
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {holdsSomething && <Button variant="secondary" onClick={() => setMoving(true)}>{t("copy.takeOut")}</Button>}
          {lead.status === "CLOSED" && <Button onClick={onApply}>{t("copy.leadAgain")}</Button>}
        </div>
      </div>
      {holdsSomething && (
        <section className={card}>
          <h2 className="text-lg font-semibold">{t("copy.holdings")}</h2>
          <Holdings lead={lead} page={page} />
        </section>
      )}
      {moving && <LeadFunds page={page} lead={lead} direction="out" onClose={() => setMoving(false)} onDone={onChange} />}
      {applying && (
        <Dialog title={t("copy.leadAgain")} onClose={onClose}>
          <Apply lead={lead} page={page} onDone={onChange} />
        </Dialog>
      )}
    </>
  );
}

/**
 * Becoming a leader, in one step: who, what they show, and what moves into the lead account.
 * The transfer's id is made once per form, so sending it twice funds once. Whether it leads at
 * once or waits for staff is the operator's setting, and the button says which.
 */
function Apply({ lead, page, onDone }: { lead: MyLead | null; page: LeadPage; onDone: () => void }) {
  const t = useT();
  const [name, setName] = useState(lead?.displayName ?? "");
  const [bio, setBio] = useState(lead?.bio ?? "");
  const [shown, setShown] = useState(lead?.portfolioShown ?? true);
  const [amount, setAmount] = useState("");
  const [transferId] = useState(() => crypto.randomUUID());
  const balances = useQuery({ queryKey: ["balances"], queryFn: ({ signal }) => fetchBalances(signal) });
  const available = balances.data?.find((b) => b.asset === page.quoteAsset)?.available ?? "0";
  const apply = useMutation({ mutationFn: applyToLead, onSuccess: onDone });
  const tooLittle = amount !== "" && Number(amount) < Number(page.minBalance);
  const tooMuch = amount !== "" && (Number(amount) > Number(page.maxBalance) || Number(amount) > Number(available));
  return (
    <form className="flex flex-col gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            apply.mutate({ displayName: name.trim(), ...(bio.trim() ? { bio: bio.trim() } : {}), showPortfolio: shown, transferId, amount });
          }}>
      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("copy.displayName")}</span>
        <input className={control} required minLength={3} maxLength={40} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("copy.bio")}</span>
        <textarea className={`${control} h-24 py-2`} maxLength={500} value={bio} onChange={(e) => setBio(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("copy.startWith", { asset: page.quoteAsset })}</span>
        <span className="flex gap-2">
          <input className={`${control} num flex-1`} inputMode="decimal" pattern="\d*\.?\d*" required value={amount}
                 onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))} />
          <Button type="button" variant="secondary" onClick={() => setAmount(available)}>{t("subAccounts.max")}</Button>
        </span>
        <span className={`num text-sm ${tooLittle || tooMuch ? "text-danger" : "text-dim"}`}>
          {t("copy.startWithRange", { min: page.minBalance, max: page.maxBalance, asset: page.quoteAsset })} · {t("copy.available", { amount: `${available} ${page.quoteAsset}` })}
        </span>
      </label>
      <label className="flex items-start gap-3 text-base">
        <input type="checkbox" className="mt-1 size-4 accent-current" checked={shown} onChange={(e) => setShown(e.target.checked)} />
        <span className="flex flex-col gap-0.5">
          <span>{t("copy.showPortfolio")}</span>
          <span className="text-sm text-dim">{t("copy.showPortfolioHint")}</span>
        </span>
      </label>
      <ol className="list-decimal space-y-1 pl-5 text-sm text-dim">
        <li>{t("copy.applyNote1", { asset: page.quoteAsset })}</li>
        <li>{t("copy.applyNote2", { min: page.minBalance, asset: page.quoteAsset })}</li>
        <li>{t("copy.applyNote3")}</li>
      </ol>
      <FormError error={apply.error} />
      <Button type="submit" className="self-start" pending={apply.isPending}
              disabled={name.trim().length < 3 || Number(amount) <= 0 || tooLittle || tooMuch}>
        {t("copy.startLeading")}
      </Button>
    </form>
  );
}

/**
 * In from the customer's own account, or back out to it. While leading only the quote asset
 * comes out, and only what is above the minimum; afterwards, anything the account holds.
 */
function LeadFunds({ page, lead, direction, onClose, onDone }: {
  page: LeadPage; lead: MyLead; direction: "in" | "out"; onClose: () => void; onDone: () => void;
}) {
  const t = useT();
  const leading = lead.status === "ACTIVE";
  const balances = useQuery({ queryKey: ["balances"], queryFn: ({ signal }) => fetchBalances(signal), enabled: direction === "in" });
  const held = lead.holdings.filter((h) => Number(h.available) > 0 && (!leading || h.asset === page.quoteAsset));
  const [asset, setAsset] = useState(page.quoteAsset);
  const [amount, setAmount] = useState("");
  const [transferId] = useState(() => crypto.randomUUID());
  const done = { onSuccess: () => { onDone(); onClose(); } };
  const put = useMutation({ mutationFn: fundLead, ...done });
  const take = useMutation({ mutationFn: withdrawLead, ...done });

  const chosen = direction === "in" ? page.quoteAsset : (held.some((h) => h.asset === asset) ? asset : (held[0]?.asset ?? page.quoteAsset));
  const holds = direction === "in"
    ? balances.data?.find((b) => b.asset === chosen)?.available ?? "0"
    : lead.holdings.find((h) => h.asset === chosen)?.available ?? "0";
  // Leading, the minimum stays behind.
  const available = direction === "out" && leading ? String(Math.max(0, Number(holds) - Number(page.minBalance))) : holds;
  const tooMuch = amount !== "" && Number(amount) > Number(available);
  const pending = put.isPending || take.isPending;
  return (
    <Dialog title={t(direction === "in" ? "copy.addFunds" : "copy.takeOut")} onClose={onClose}>
      <form className="flex flex-col gap-4" onSubmit={(e) => {
        e.preventDefault();
        if (direction === "in") put.mutate({ transferId, amount });
        else take.mutate({ transferId, asset: chosen, amount });
      }}>
        {direction === "out" && !leading && held.length > 1 && (
          <SelectFilter label={t("copy.asset")} value={chosen} onChange={setAsset} options={held.map((h) => ({ value: h.asset, label: h.asset }))} />
        )}
        <label className="flex flex-col gap-1.5">
          <span className="text-sm font-medium text-muted">{t("copy.amount", { asset: chosen })}</span>
          <input className={`${control} num`} inputMode="decimal" pattern="\d*\.?\d*" required autoFocus value={amount}
                 onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))} />
          <span className={`num text-sm ${tooMuch ? "text-danger" : "text-dim"}`}>{t("copy.available", { amount: `${available} ${chosen}` })}</span>
        </label>
        <FormError error={put.error ?? take.error} />
        <div className="flex gap-2">
          <Button type="submit" pending={pending} disabled={Number(amount) <= 0 || tooMuch}>{t("common.confirm")}</Button>
          <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
        </div>
      </form>
    </Dialog>
  );
}
