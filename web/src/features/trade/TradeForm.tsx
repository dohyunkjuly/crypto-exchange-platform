"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { useToast } from "@/components/ui/Toast";
import { useSession } from "@/features/auth/session";
import type { MarketSymbol } from "@/features/markets/api";
import { balancesKey, fetchBalances, fetchFees, fetchKyc, fetchMfa, openOrdersKey } from "@/features/my/shared/api";
import { useT, useTOptional } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { price as fmt } from "@/lib/format";
import { api } from "@/lib/bff/client";
import { scaleOf } from "@/lib/money";
import { tradeBlockers } from "./eligibility";
import type { TradingAccount } from "./tradingAccount";
import { baseForQuote, CONDITIONAL, isConditional, isMarketFiring, sizeForShare, sizing, toRequest, totalOf, validate, type Draft, type OrderType, type Side } from "./formMath";

/** 202: queued, not matched. Never retried (AGENTS.md) -- a retry is a second order. */
const placeOrder = (o: ReturnType<typeof toRequest>, account: TradingAccount) => api<{ orderId: string }>("/bff/orders", { method: "POST", body: o, account });

const input = "num h-10 w-full rounded-lg border border-border bg-surface-raised px-3 text-sm text-text outline-none focus:border-border-strong disabled:opacity-50";
const SHARES = [0, 0.25, 0.5, 0.75, 1];
type Tab = "LIMIT" | "MARKET" | "TPSL";

/**
 * The order form, sized as docs/reference/order-types.md requires: a market-firing BUY takes a
 * quote budget (Total), everything else a base amount; market-firing types have no price; the
 * conditional types take a trigger price or a trailing distance. Signed out it is a log-in
 * button; an account the server would refuse sees what to fix first.
 *
 * `account` is the one the order is placed as; `onAccount` is there only for a customer with
 * a lead account, and is the switch between the two.
 */
export function TradeForm({ market: m, pickedPrice, bestBid, bestAsk, account, onAccount }: {
  market: MarketSymbol; pickedPrice: string | null; bestBid: string | null; bestAsk: string | null;
  account?: TradingAccount; onAccount?: (account: TradingAccount) => void;
}) {
  const t = useT();
  const tOpt = useTOptional();
  const show = useToast();
  const describe = useDescribe();
  const session = useSession();
  const queryClient = useQueryClient();
  const [side, setSide] = useState<Side>("BUY");
  const [tab, setTab] = useState<Tab>("LIMIT");
  const [conditional, setConditional] = useState<OrderType>("STOP_LOSS_LIMIT");
  const [postOnly, setPostOnly] = useState(false);
  const [f, setF] = useState({ price: "", stopPrice: "", amount: "", total: "", trailingPct: "" });
  const [share, setShare] = useState(0);
  const [picked, setPicked] = useState<string | null>(null);
  // A click on the book sets the price: derived state, applied during render when the pick changes.
  if (pickedPrice !== picked) { setPicked(pickedPrice); if (pickedPrice) setF((x) => ({ ...x, price: pickedPrice })); }

  const type: OrderType = tab === "TPSL" ? conditional : tab === "LIMIT" && postOnly ? "LIMIT_MAKER" : tab;
  const draft: Draft = { type, side, ...f };
  const kind = sizing(type, side);
  const marketFiring = isMarketFiring(type);
  const signedIn = session.status === "signedIn";
  const balances = useQuery({ queryKey: balancesKey(account), queryFn: ({ signal }) => fetchBalances(signal, account), enabled: signedIn });
  const fees = useQuery({ queryKey: ["fees"], queryFn: ({ signal }) => fetchFees(signal), enabled: signedIn });
  const kyc = useQuery({ queryKey: ["kyc"], queryFn: ({ signal }) => fetchKyc(signal), enabled: signedIn });
  const mfa = useQuery({ queryKey: ["mfa"], queryFn: ({ signal }) => fetchMfa(signal), enabled: signedIn });
  const place = useMutation({
    mutationFn: (o: ReturnType<typeof toRequest>) => placeOrder(o, account),
    // 202 means queued, not matched, so nothing is announced here: the socket brings the
    // outcome a moment later and that is what the toast reports.
    onSuccess: () => { setF((x) => ({ ...x, amount: "", total: "" })); setShare(0); queryClient.invalidateQueries({ queryKey: openOrdersKey(account), exact: true }); queryClient.invalidateQueries({ queryKey: balancesKey(account) }); },
    // The form keeps showing the error too; the toast is for a user whose eyes have moved on.
    onError: (e) => show({ tone: "error", title: t("toast.failed"), body: describe(e) ?? undefined }),
  });

  const spend = side === "BUY" ? m.quoteAsset : m.baseAsset;
  const available = balances.data?.find((b) => b.asset === spend)?.available ?? null;
  const blockers = session.status === "signedIn" && kyc.data && mfa.data ? tradeBlockers(session.user.emailVerified, kyc.data, mfa.data, new Date()) : [];
  const pd = scaleOf(m.priceTick); const qd = scaleOf(m.qtyStep);
  // Market-firing orders have no price; the top of the book is the estimate shown.
  const refPrice = marketFiring ? (side === "BUY" ? bestAsk : bestBid) ?? "" : f.price;
  const problems = validate(draft, m);
  const problemText = problems[0] ? tOpt(`tradeForm.problem.${problems[0]}`, { min: m.minQty, max: m.maxQty, notional: m.minNotional, asset: m.quoteAsset }) : null;
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => { setF((x) => ({ ...x, [k]: e.target.value })); if (k === "amount" || k === "total") setShare(0); };
  const pickShare = (s: number) => {
    setShare(s);
    if (!available) return;
    const v = sizeForShare(s, available, kind, refPrice, m);
    setF((x) => (kind === "quote" ? { ...x, total: v } : { ...x, amount: v }));
  };
  const seg = (on: boolean, tone: "up" | "down") => `flex-1 rounded-md py-1.5 text-sm font-medium transition-colors ${on ? (tone === "up" ? "bg-up text-white" : "bg-down text-white") : "text-muted hover:text-text"}`;
  const typeTab = (on: boolean) => `text-sm ${on ? "text-text" : "text-muted hover:text-text"}`;
  const field = (label: string, children: React.ReactNode) => <label className="flex flex-col gap-1 text-xs text-muted">{label}{children}</label>;

  return (
    <div className="flex flex-col gap-4 p-4">
      <div className="flex items-center justify-between text-sm font-semibold">
        {t("trade.tradeForm")}
        {onAccount && (
          <select aria-label={t("trade.account")} value={account ?? ""} onChange={(e) => onAccount(e.target.value === "lead" ? "lead" : undefined)}
                  className="h-7 rounded-md border border-border bg-surface-raised px-2 text-xs font-normal text-text outline-none">
            <option value="">{t("trade.accountOwn")}</option>
            <option value="lead">{t("trade.accountLead")}</option>
          </select>
        )}
      </div>
      <div className="flex rounded-lg border border-border p-1">
        <button type="button" className={seg(side === "BUY", "up")} onClick={() => setSide("BUY")}>{t("side.BUY")}</button>
        <button type="button" className={seg(side === "SELL", "down")} onClick={() => setSide("SELL")}>{t("side.SELL")}</button>
      </div>
      <div className="flex items-center gap-4">
        {(["LIMIT", "MARKET", "TPSL"] as const).map((x) => (
          <button key={x} type="button" className={typeTab(tab === x)} onClick={() => setTab(x)} disabled={x === "TPSL" && !m.stopOrdersEnabled}>
            {t(x === "LIMIT" ? "orderType.LIMIT" : x === "MARKET" ? "orderType.MARKET" : "tradeForm.tpsl")}
          </button>
        ))}
        {tab === "TPSL" && (
          <select aria-label={t("tradeForm.conditionalType")} value={conditional} onChange={(e) => setConditional(e.target.value as OrderType)}
                  className="ml-auto h-7 rounded-md border border-border bg-surface-raised px-2 text-xs text-text outline-none">
            {CONDITIONAL.map((x) => <option key={x} value={x}>{tOpt(`orderType.${x}`) ?? x}</option>)}
          </select>
        )}
      </div>
      <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); if (problems.length === 0) place.mutate(toRequest(draft, m)); }}>
        {isConditional(type) && (
          <>
            {field(t("tradeForm.triggerPrice", { asset: m.quoteAsset }), <input className={input} inputMode="decimal" value={f.stopPrice} onChange={set("stopPrice")} placeholder={f.trailingPct ? t("tradeForm.activationOptional") : fmt("0", pd)} />)}
            {field(t("tradeForm.trailing"), <input className={input} inputMode="decimal" value={f.trailingPct} onChange={set("trailingPct")} placeholder={t("tradeForm.trailingOptional")} />)}
          </>
        )}
        {field(t("tradeForm.price", { asset: m.quoteAsset }), marketFiring ? (
          // Not an input: a market order has no price to type, and a field that looks typeable but is not reads as a bug.
          <div className="flex h-10 items-center rounded-lg border border-dashed border-border-strong px-3 text-sm text-muted">{t("tradeForm.marketPrice")}</div>
        ) : (
          <div className="flex gap-2">
            <input className={input} inputMode="decimal" value={f.price} onChange={set("price")} placeholder={fmt("0", pd)} />
            <button type="button" onClick={() => { const b = side === "BUY" ? bestAsk : bestBid; if (b) setF((x) => ({ ...x, price: b })); }} disabled={!(side === "BUY" ? bestAsk : bestBid)}
                    className="shrink-0 rounded-lg border border-border px-3 text-xs font-medium text-muted hover:text-text disabled:opacity-40">{t("tradeForm.best")}</button>
          </div>
        ))}
        {kind === "quote" ? (
          <>
            {field(t("tradeForm.total", { asset: m.quoteAsset }), <input className={input} inputMode="decimal" value={f.total} onChange={set("total")} placeholder={t("tradeForm.min", { min: fmt(m.minNotional, m.quoteDecimals) })} />)}
            <div className="flex justify-between text-xs text-muted"><span>{t("tradeForm.estAmount", { asset: m.baseAsset })}</span><span className="num">{refPrice ? baseForQuote(f.total, refPrice, m) || "—" : "—"}</span></div>
          </>
        ) : (
          field(t("tradeForm.amount", { asset: m.baseAsset }), <input className={input} inputMode="decimal" value={f.amount} onChange={set("amount")} placeholder={t("tradeForm.min", { min: fmt(m.minQty, qd) })} />)
        )}
        <div role="group" aria-label={t("tradeForm.share")} className="flex items-center justify-between px-1">
          {SHARES.map((s) => (
            <button key={s} type="button" aria-pressed={share === s} onClick={() => pickShare(s)} disabled={!available}
                    className={`flex flex-col items-center gap-1 text-[10px] ${share >= s && share > 0 ? "text-text" : "text-dim"} disabled:opacity-40`}>
              <span className={`size-2 rounded-full border ${share >= s && share > 0 ? "border-text bg-text" : "border-border-strong"}`} />{s * 100}%
            </button>
          ))}
        </div>
        {kind === "base" && (
          <div className="flex justify-between text-xs text-muted">
            <span>{marketFiring ? t("tradeForm.estTotal", { asset: m.quoteAsset }) : t("tradeForm.total", { asset: m.quoteAsset })}</span>
            <span className="num">{totalOf(refPrice, f.amount) ? fmt(totalOf(refPrice, f.amount), m.quoteDecimals) : "—"}</span>
          </div>
        )}
        {tab === "LIMIT" && (
          <label className="flex items-center gap-2 text-xs text-muted"><input type="checkbox" checked={postOnly} onChange={(e) => setPostOnly(e.target.checked)} className="size-3.5 accent-current" />{t("tradeForm.postOnly")}</label>
        )}
        <div className="flex justify-between text-xs text-muted">
          <span>{t("tradeForm.available")}</span>
          <span className="num text-text">{available ? `${fmt(available, side === "BUY" ? m.quoteDecimals : qd)} ${spend}` : "--"}</span>
        </div>
        {m.paused ? (
          <p role="alert" className="mt-1 rounded-lg border border-border p-3 text-xs text-muted">{t("tradeForm.paused", { pair: `${m.baseAsset}/${m.quoteAsset}` })}</p>
        ) : !signedIn ? (
          <LocaleLink href="/login" className={`mt-1 inline-flex h-11 items-center justify-center rounded-full text-sm font-medium text-white ${side === "BUY" ? "bg-up" : "bg-down"}`}>{t("tradeForm.logIn")}</LocaleLink>
        ) : blockers.length > 0 ? (
          <div className="mt-1 flex flex-col gap-2 rounded-lg border border-border p-3 text-xs">
            <span className="text-muted">{t(`tradeForm.need.${blockers[0]}`)}</span>
            <LocaleLink href={blockers[0] === "email" ? "/verify-email" : blockers[0] === "kyc" ? "/my/account/identification" : "/my/account/security/authenticator"} className="text-text underline underline-offset-4">{t("tradeForm.fixIt")}</LocaleLink>
          </div>
        ) : (
          <>
            {(f.amount || f.total) && problemText && <p className="text-xs text-dim">{problemText}</p>}
            <FormError error={place.error} />
            {place.isSuccess && <p role="status" className="text-xs text-muted">{t("tradeForm.placed")}</p>}
            <Button type="submit" block variant={side === "BUY" ? "up" : "down"} pending={place.isPending} disabled={problems.length > 0} size="lg" className="mt-1 rounded-full">
              {t(side === "BUY" ? "tradeForm.buy" : "tradeForm.sell", { asset: m.baseAsset })}
            </Button>
          </>
        )}
        {signedIn && fees.data && <p className="text-xs text-muted">{t("tradeForm.fees", { maker: fees.data.makerPercent, taker: fees.data.takerPercent })}</p>}
      </form>
    </div>
  );
}
