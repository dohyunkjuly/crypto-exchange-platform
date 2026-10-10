"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Dialog } from "@/components/ui/Dialog";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { BackLink } from "@/features/my/shared/BackLink";
import { Pager } from "@/features/my/shared/Pager";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { fetchBalances } from "@/features/my/shared/api";
import { useLocale, useT } from "@/lib/i18n";
import { addFunds, fetchCopies, fetchFollow, fetchLeaders, fetchShares, PER_PAGE, removeFunds, stopCopying, type Follow } from "./api";
import { resultTone, statusTone } from "./status";

const control = "h-10 rounded-lg border border-border bg-surface-raised px-3 text-base text-text outline-none focus:border-border-strong";

/**
 * One copy account: its profit share first, then what it holds, what it copied and what it paid
 * the leader, a tab each; and what its owner may do -- add funds, take funds out, stop, or once
 * closed, copy the leader again. It never offers an order: only the copier trades it.
 */
export function CopyAccount({ id }: { id: string }) {
  const t = useT();
  const locale = useLocale();
  const describe = useDescribe();
  const queryClient = useQueryClient();
  const terms = useQuery({ queryKey: ["copy-leaders"], queryFn: ({ signal }) => fetchLeaders(signal), staleTime: 60_000 }).data?.terms;
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  // Polled: a copy lands, or the account closes, without anything this page did.
  const detail = useQuery({ queryKey: ["copy-follow", id], queryFn: ({ signal }) => fetchFollow(id, signal), refetchInterval: 5_000 });
  const [moving, setMoving] = useState<"in" | "out" | null>(null);
  const [stopping, setStopping] = useState(false);
  const [tab, setTab] = useState<"holdings" | "copies" | "profitShare">("holdings");
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["copy-follow", id] });
    queryClient.invalidateQueries({ queryKey: ["copy-follows"] });
    queryClient.invalidateQueries({ queryKey: ["balances"] });
  };
  const stop = useMutation({ mutationFn: stopCopying, onSettled: () => { setStopping(false); refresh(); } });

  if (detail.error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(detail.error)}</p>;
  if (!detail.data) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;

  const { follow } = detail.data;
  const quoteAsset = terms?.quoteAsset ?? "";
  const active = follow.status === "ACTIVE";
  const realized = Number(follow.realizedProfit);
  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/copy/mine" label={t("copy.myCopies")} />
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">{follow.leaderName}</h1>
          <p className="text-base">
            <span className={statusTone(follow.status)}>{t(`copyStatus.${follow.status}`)}</span>
            {follow.closeReason && <span className="text-muted"> · {t(`copyClosed.${follow.closeReason}`)}</span>}
            <span className="text-muted"> · {t("copy.slippageShown", { percent: follow.slippageCapBps / 100 })}</span>
            {active && follow.failuresInRow > 0 && <span className="text-danger"> · {t("copy.failures", { n: follow.failuresInRow })}</span>}
          </p>
        </div>
        {active && (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => setMoving("in")}>{t("copy.addFunds")}</Button>
            <Button variant="secondary" onClick={() => setMoving("out")}>{t("copy.takeOut")}</Button>
            <Button variant="ghost" onClick={() => setStopping(true)}>{t("copy.stop")}</Button>
          </div>
        )}
        {/* A closed account is never reopened: copying again is a new follow, on the leader's form. */}
        {follow.status === "CLOSED" && (
          <LocaleLink href={`/copy/${follow.leaderId}?copy=1`}><Button>{t("copy.copyAgain")}</Button></LocaleLink>
        )}
      </div>
      <FormError error={stop.error} />
      {stopping && (
        <ConfirmDialog title={t("copy.stop")} message={t("copy.stopConfirm")} pending={stop.isPending}
                       onConfirm={() => stop.mutate(id)} onClose={() => setStopping(false)} />
      )}
      {moving && <Funds follow={follow} direction={moving} onClose={() => setMoving(null)} onDone={refresh} />}

      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="rounded-lg border border-border p-4"><dt className="text-sm text-muted">{t("copy.realizedProfit")}</dt>
          <dd className={`num text-xl font-semibold ${realized > 0 ? "text-up" : realized < 0 ? "text-down" : ""}`}>{follow.realizedProfit} {quoteAsset}</dd></div>
        <div className="rounded-lg border border-border p-4"><dt className="text-sm text-muted">{t("copy.sharedProfit")}</dt>
          <dd className="num text-xl font-semibold">{follow.sharedProfit} {quoteAsset}</dd></div>
        <div className="rounded-lg border border-border p-4"><dt className="text-sm text-muted">{t("copy.yourRate")}</dt>
          <dd className="num text-xl font-semibold">{follow.profitShareBps / 100}%</dd></div>
      </dl>

      <div role="tablist" className="flex gap-6 border-b border-border">
        {(["holdings", "copies", "profitShare"] as const).map((x) => (
          <button key={x} role="tab" aria-selected={tab === x} onClick={() => setTab(x)}
                  className={`-mb-px border-b-2 pb-2 text-base font-medium ${tab === x ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>
            {t(`copy.${x}`)}
          </button>
        ))}
      </div>

      {tab === "holdings" && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full whitespace-nowrap text-base">
            <thead><tr className="border-b border-border text-left text-sm text-muted">
              <th className="px-4 py-3 font-medium">{t("copy.asset")}</th>
              <th className="px-4 py-3 text-right font-medium">{t("copy.availableCol")}</th>
              <th className="px-4 py-3 text-right font-medium">{t("copy.inOrders")}</th>
            </tr></thead>
            <tbody>
              {follow.holdings.map((h) => (
                <tr key={h.asset} className="border-b border-border last:border-0">
                  <td className="px-4 py-3 font-medium">{h.asset}</td>
                  <td className="num px-4 py-3 text-right">{h.available}</td>
                  <td className="num px-4 py-3 text-right text-muted">{h.locked}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {follow.holdings.length === 0 && <Empty>{t("copy.noHoldings")}</Empty>}
        </div>
      )}

      {tab === "copies" && <Copies id={id} />}

      {tab === "profitShare" && <Shares id={id} date={date} />}
    </div>
  );
}

/** What the account has paid the leader, a settlement a row, a page at a time from the server. */
function Shares({ id, date }: { id: string; date: (iso: string) => string }) {
  const t = useT();
  const [page, setPage] = useState(0);
  const rows = useQuery({ queryKey: ["copy-follow-shares", id, page], queryFn: ({ signal }) => fetchShares(id, page, signal),
    placeholderData: (previous) => previous }).data ?? [];
  return (
    <div className="flex flex-col gap-4">
      <div className="overflow-x-auto rounded-lg border border-border">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="px-4 py-3 font-medium">{t("copy.time")}</th>
            <th className="px-4 py-3 font-medium">{t("copy.settledOn")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("copy.due")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("copy.paid")}</th>
          </tr></thead>
          <tbody>
            {rows.map((s) => (
              <tr key={s.id} className="border-b border-border last:border-0">
                <td className="num px-4 py-3 text-muted">{date(s.createdAt)}</td>
                <td className="px-4 py-3">{t(`copyShareTrigger.${s.trigger}`)}</td>
                <td className="num px-4 py-3 text-right">{s.due}</td>
                <td className="num px-4 py-3 text-right">{s.paid}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <Empty>{t("copy.noShares")}</Empty>}
      </div>
      <Pager current={page} hasNext={rows.length === PER_PAGE} onPage={setPage} />
    </div>
  );
}

/** The account's copies, a page at a time from the server, each with what its order traded. */
function Copies({ id }: { id: string }) {
  const t = useT();
  const locale = useLocale();
  const [page, setPage] = useState(0);
  // Polled like the account: a copy lands without anything this page did.
  const rows = useQuery({ queryKey: ["copy-follow-copies", id, page], queryFn: ({ signal }) => fetchCopies(id, page, signal),
    refetchInterval: 5_000, placeholderData: (previous) => previous }).data ?? [];
  const time = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  const num = (v: string | null) => v === null ? "—" : Number(v).toLocaleString(undefined, { maximumFractionDigits: 8 });
  return (
    <div className="flex flex-col gap-4">
      <div className="overflow-x-auto rounded-lg border border-border">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="px-4 py-3 font-medium">{t("copy.time")}</th>
            <th className="px-4 py-3 font-medium">{t("copy.market")}</th>
            <th className="px-4 py-3 font-medium">{t("copy.side")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("copy.filled")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("copy.avgPrice")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("copy.total")}</th>
            <th className="px-4 py-3 font-medium">{t("copy.result")}</th>
          </tr></thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} className="border-b border-border last:border-0">
                <td className="num px-4 py-3 text-muted">{time(c.createdAt)}</td>
                <td className="px-4 py-3 font-medium">{c.symbol}</td>
                <td className={`px-4 py-3 ${c.side === "BUY" ? "text-up" : "text-down"}`}>
                  {t(`side.${c.side}`)}{c.closing && <span className="text-muted"> · {t("copy.closingSale")}</span>}
                </td>
                <td className="num px-4 py-3 text-right">{num(c.filled)}</td>
                <td className="num px-4 py-3 text-right">{num(c.averagePrice)}</td>
                <td className="num px-4 py-3 text-right">{num(c.total)}</td>
                <td className={`px-4 py-3 ${resultTone(c.status)}`}>
                  {t(`copyResult.${c.status}`)}{c.reason && <span className="text-muted"> · {t(`copyReason.${c.reason}`)}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <Empty>{t("copy.noCopiesYet")}</Empty>}
      </div>
      <Pager current={page} hasNext={rows.length === PER_PAGE} onPage={setPage} />
    </div>
  );
}

/** In from the main account, or back out to it. The transfer's id is made once per dialog, so a retry moves the money once. */
function Funds({ follow, direction, onClose, onDone }: { follow: Follow; direction: "in" | "out"; onClose: () => void; onDone: () => void }) {
  const t = useT();
  const terms = useQuery({ queryKey: ["copy-leaders"], queryFn: ({ signal }) => fetchLeaders(signal), staleTime: 60_000 }).data?.terms;
  const balances = useQuery({ queryKey: ["balances"], queryFn: ({ signal }) => fetchBalances(signal), enabled: direction === "in" });
  const [amount, setAmount] = useState("");
  const [transferId] = useState(() => crypto.randomUUID());
  const move = useMutation({ mutationFn: direction === "in" ? addFunds : removeFunds, onSuccess: () => { onDone(); onClose(); } });

  const quote = terms?.quoteAsset ?? "";
  const available = direction === "in"
    ? balances.data?.find((b) => b.asset === quote)?.available ?? "0"
    : follow.holdings.find((h) => h.asset === quote)?.available ?? "0";
  const tooMuch = amount !== "" && Number(amount) > Number(available);
  return (
    <Dialog title={t(direction === "in" ? "copy.addFunds" : "copy.takeOut")} onClose={onClose}>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); move.mutate({ id: follow.id, transferId, amount }); }}>
        <label className="flex flex-col gap-1.5">
          <span className="text-sm font-medium text-muted">{t("copy.amount", { asset: quote })}</span>
          <input className={`${control} num`} inputMode="decimal" pattern="\d*\.?\d*" required autoFocus value={amount}
                 onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))} />
          <span className={`num text-sm ${tooMuch ? "text-danger" : "text-dim"}`}>{t("copy.available", { amount: `${available} ${quote}` })}</span>
        </label>
        <FormError error={move.error} />
        <div className="flex gap-2">
          <Button type="submit" pending={move.isPending} disabled={Number(amount) <= 0 || tooMuch}>{t("common.confirm")}</Button>
          <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
        </div>
      </form>
    </Dialog>
  );
}
