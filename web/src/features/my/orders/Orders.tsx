"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { Pager } from "../shared/Pager";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { fetchSymbols, type MarketSymbol } from "@/features/markets/api";
import { useLocale, useT, useTOptional } from "@/lib/i18n";
import { price as fmt } from "@/lib/format";
import { multiply, scaleOf } from "@/lib/money";
import { cancelAllOrders, cancelOrder, fetchMyTrades, fetchOrderHistory, fetchOrders, type CancelAllFilter, type HistoryFilter, type MyTrade, type Order, openOrdersKey } from "../shared/api";
import { filterOpen, inKind, lastDays, toInstants, type DayRange, type OpenFilter, type OpenKind } from "../shared/filterMath";
import { DateRangeFilter, FilterBar, PairPicker, SelectFilter, useToday } from "../shared/filters";
import { AmendOrder } from "./AmendOrder";
import { amendableFields, type AmendField } from "./amendMath";
import { isCancelable, triggerOf } from "./orderMath";

type Tab = "open" | "triggers" | "history" | "trades";

/**
 * `sub`: which account's orders. Undefined is the signed-in account's own; a number is one of
 * its sub-accounts, read and cancelled by its master. No socket carries a sub's frames to the
 * master's browser, so the open list polls instead, and nothing is amended. "lead" is its lead
 * account, traded from this session like its own: a socket of its own, and amends.
 *
 * `null` is "there is no sub-account to read": the page renders whole and empty and asks for
 * nothing, because a request for an account that does not exist is an error, not an empty list.
 */
export type Sub = number | "lead" | null;
const nothingToRead = (sub?: Sub) => sub === null;
/** Never the master's key for a sub, and never a sub's for the master. */
const subKey = (sub?: Sub) => (sub === null ? "none" : sub ?? 0);
/** Rows to start from when nothing will be fetched, so the table is empty rather than loading. */
/**
 * Rows a page of history shows. The account pages keep it short -- a wall of fills is
 * overwhelming, and the filters above are how one narrows, not scrolling. The trade screen's
 * bottom panel scrolls inside a fixed height, so it takes the server's default.
 */
const pageSize = (compact: boolean) => (compact ? 50 : 20);

export function Orders({ sub }: { sub?: Sub } = {}) {
  const t = useT();
  const [tab, setTab] = useState<Tab>("open");
  // The same cache entry the tabs read, so the counts cost nothing and follow the socket.
  const open = useQuery({
    queryKey: openOrdersKey(sub), queryFn: ({ signal }) => fetchOrders(signal, sub ?? undefined),
    enabled: !nothingToRead(sub), initialData: nothingToRead(sub) ? [] : undefined,
  });
  const count = (kind: OpenKind) => (open.data ? ` (${open.data.filter((o) => inKind(o, kind)).length})` : "");
  const tabs: { id: Tab; label: string }[] = [
    { id: "open", label: t("orders.open") + count("book") },
    { id: "triggers", label: t("orders.triggerOrders") + count("triggers") },
    { id: "history", label: t("orders.history") },
    { id: "trades", label: t("orders.trades") },
  ];
  return (
    <div className="flex flex-col gap-4">
      <h1 className="sr-only">{t("orders.title")}</h1>
      <div role="tablist" className="flex gap-6 border-b border-border">
        {tabs.map((x) => (
          <button key={x.id} role="tab" aria-selected={tab === x.id} onClick={() => setTab(x.id)}
                  className={`-mb-px border-b-2 pb-2 text-base ${tab === x.id ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>
            {x.label}
          </button>
        ))}
      </div>
      {tab === "open" ? <OpenOrders key="book" kind="book" sub={sub} />
        : tab === "triggers" ? <OpenOrders key="triggers" kind="triggers" sub={sub} />
          : tab === "history" ? <OrderHistory sub={sub} /> : <TradeHistory sub={sub} />}
    </div>
  );
}

/** Symbol configuration, for the pair's two halves and the precision to render at. */
function useMarkets() {
  const { data } = useQuery({ queryKey: ["symbols"], queryFn: ({ signal }) => fetchSymbols(signal), staleTime: 5 * 60_000 });
  return new Map((data ?? []).map((m) => [m.symbol, m]));
}
const sorted = (markets: Map<string, MarketSymbol>) => [...markets.values()].sort((a, b) => a.symbol.localeCompare(b.symbol));

type SideValue = "" | "BUY" | "SELL";
/** What can be in each tab: a plain stop fires as a market order and never rests; a _LIMIT one rests once fired. */
const BOOK_TYPES = ["LIMIT", "LIMIT_MAKER", "STOP_LOSS_LIMIT", "TAKE_PROFIT_LIMIT"];
const TRIGGER_TYPES = ["STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT"];
const NO_OPEN_FILTER: OpenFilter = { symbol: "", side: "", type: "" };

function SideFilter({ value, onChange, compact }: { value: SideValue; onChange: (v: SideValue) => void; compact?: boolean }) {
  const t = useT();
  return (
    <SelectFilter<SideValue> label={t("filters.side")} value={value} onChange={onChange} compact={compact}
                             options={[{ value: "", label: t("filters.all") }, { value: "BUY", label: t("side.BUY") }, { value: "SELL", label: t("side.SELL") }]} />
  );
}

/** History filters as the query sends them: "" dropped, days turned into instants. */
function historyFilter(range: DayRange, symbol: string, side: SideValue, timeField?: HistoryFilter["timeField"]): HistoryFilter {
  return { ...toInstants(range), symbol: symbol || undefined, side: side || undefined, timeField };
}
const pxDigits = (m?: MarketSymbol) => (m ? scaleOf(m.priceTick) : 8);
const qtyDigits = (m?: MarketSymbol) => (m ? scaleOf(m.qtyStep) : 8);
const quoteDigits = (m?: MarketSymbol) => (m ? m.quoteDecimals : 8);

function useTime() {
  const locale = useLocale();
  const f = new Intl.DateTimeFormat(locale, { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
  return (iso: string) => f.format(new Date(iso));
}

/** `lockSymbol` pins the pair filter and hides the picker -- the trading screen's "this pair" mode. */
/** `compact` is the trading screen's bottom panel: no filter bar, denser rows. */
export function OpenOrders({ kind, lockSymbol, compact = false, sub }: { kind: OpenKind; lockSymbol?: string; compact?: boolean; sub?: Sub }) {
  const t = useT();
  const tOpt = useTOptional();
  const time = useTime();
  const markets = useMarkets();
  const queryClient = useQueryClient();
  const show = useToast();
  const describe = useDescribe();
  // Rows whose cancel the engine has accepted but not yet reported; the socket's orderUpdate
  // removes them. Until then each says so instead of looking stuck.
  const [canceling, setCanceling] = useState<Set<string>>(new Set());
  const orders = useQuery({
    queryKey: openOrdersKey(sub),
    queryFn: ({ signal }) => fetchOrders(signal, sub ?? undefined),
    enabled: !nothingToRead(sub),
    initialData: nothingToRead(sub) ? [] : undefined,
    // A fallback for a dropped socket, and only while a canceled row is still listed. A sub's
    // list has no socket at all, so it polls the whole time.
    refetchInterval: (q) => (typeof sub === "number" || q.state.data?.some((o) => canceling.has(o.orderId)) ? 3_000 : false),
  });
  const [filter, setFilter] = useState<OpenFilter>({ ...NO_OPEN_FILTER, symbol: lockSymbol ?? "" });
  const [confirming, setConfirming] = useState(false);
  const [amending, setAmending] = useState<string | null>(null);
  const triggers = kind === "triggers";
  const mine = (orders.data ?? []).filter((o) => inKind(o, kind));
  const shown = filterOpen(mine, filter);
  // What "Cancel all" means right now: this tab, narrowed by its filters.
  const cancelable = shown.filter((o) => isCancelable(o.status) && !canceling.has(o.orderId));

  // A success changes nothing yet -- the engine's frames remove the rows. A failure means the
  // list may be wrong (the order filled first, say), so it is re-read.
  const resync = () => queryClient.invalidateQueries({ queryKey: openOrdersKey(sub), exact: true });
  const cancel = useMutation({
    mutationFn: (orderId: string) => cancelOrder({ orderId, sub: sub ?? undefined }),
    // The row says "canceling"; the toast waits for the engine's frame.
    onSuccess: (_r, id) => { setCanceling((s) => new Set(s).add(id)); },
    onError: (e) => { resync(); show({ tone: "error", title: t("toast.failed"), body: describe(e) ?? undefined }); },
  });
  const cancelAll = useMutation({
    mutationFn: ({ filter }: { filter: CancelAllFilter; ids: string[] }) => cancelAllOrders(filter, sub ?? undefined),
    onSuccess: (_r, { ids }) => { setCanceling((s) => new Set([...s, ...ids])); },
    onError: (e) => { resync(); show({ tone: "error", title: t("toast.failed"), body: describe(e) ?? undefined }); },
    onSettled: () => setConfirming(false),
  });
  const confirmCancelAll = () => cancelAll.mutate({
    filter: { scope: triggers ? "TRIGGERS" : "BOOK", symbol: filter.symbol || undefined, side: filter.side || undefined, type: filter.type || undefined },
    ids: cancelable.map((o) => o.orderId),
  });

  const cancelAllButton = (
    <button key="all" onClick={() => setConfirming(true)} disabled={!cancelable.length || cancelAll.isPending}
            className="font-medium text-text hover:underline disabled:text-dim disabled:no-underline">{t("orders.cancelAll")}</button>
  );
  // One amend at a time per order (5026), so the row says so instead of offering a second.
  const cancelCell = (o: Order) =>
    canceling.has(o.orderId) || o.status === "PENDING_CANCEL"
      ? <span key="c" className="text-muted">{t("orders.canceling")}</span>
      : isCancelable(o.status)
        ? <span key="c" className="inline-flex items-center gap-3">
            {o.amendRejectReason && <span role="status" className="text-sm text-danger">{tOpt(`orders.amendRejected.${o.amendRejectReason}`) ?? t("orders.amendRejected.other")}</span>}
            {o.amendPending && <span className="text-muted">{t("orders.amending")}</span>}
            <button onClick={() => cancel.mutate(o.orderId)} disabled={cancel.isPending && cancel.variables === o.orderId}
                    className="text-text hover:underline disabled:opacity-50">{t("orders.cancel")}</button>
          </span>
        : null;
  const amendingOrder = amending ? shown.find((o) => o.orderId === amending) : undefined;
  const amendingMarket = amendingOrder && markets.get(amendingOrder.symbol);
  const lead = [t("orders.time"), t("orders.pair"), t("orders.type"), t("orders.side")];

  return (
    <div className="flex flex-col gap-4">
    {!compact && <FilterBar onReset={() => setFilter({ ...NO_OPEN_FILTER, symbol: lockSymbol ?? "" })}>
      {!lockSymbol && <PairPicker value={filter.symbol} onChange={(symbol) => setFilter((f) => ({ ...f, symbol }))} markets={sorted(markets)} />}
      <SideFilter value={filter.side} onChange={(side) => setFilter((f) => ({ ...f, side }))} />
      <SelectFilter label={t("filters.type")} value={filter.type} onChange={(type) => setFilter((f) => ({ ...f, type }))}
                    options={[{ value: "", label: t("filters.all") }, ...(triggers ? TRIGGER_TYPES : BOOK_TYPES).map((v) => ({ value: v, label: tOpt(`orderType.${v}`) ?? v }))]} />
    </FilterBar>}
    <Table
      compact={compact}
      loading={orders.isPending}
      // A failed refetch keeps the rows it already has; the error replaces nothing but a blank.
      error={orders.data ? undefined : orders.error}
      empty={mine.length ? t("orders.emptyFiltered") : t(triggers ? "orders.emptyTriggers" : "orders.emptyOpen")}
      head={triggers
        ? [...lead, t("orders.trigger"), t("orders.price"), t("orders.amount"), cancelAllButton]
        : [...lead, t("orders.price"), t("orders.amount"), t("orders.filled"), t("orders.total"), cancelAllButton]}
      right={triggers ? [5, 6, 7] : [4, 5, 6, 7, 8]}
      rows={shown.map((o) => {
        const m = markets.get(o.symbol);
        // One amend in flight per order (5026), so a pending row offers no second pencil.
        const editable = typeof sub !== "number" && isCancelable(o.status) && !o.amendPending && !canceling.has(o.orderId)
          ? amendableFields(o) : [];
        const edit = (field: AmendField) => (editable.includes(field) ? () => setAmending(o.orderId) : undefined);
        const first = [
          <span key="t" className="num text-muted">{time(o.createdAt)}</span>,
          <Pair key="p" symbol={o.symbol} market={m} />,
          tOpt(`orderType.${o.orderType}`) ?? o.orderType,
          <Side key="s" side={o.side} />,
        ];
        const price = (
          <span key="px" className="num">
            <Editable onEdit={edit("price")}>{o.price ? fmt(o.price, pxDigits(m)) : t("orders.market")}</Editable>
          </span>
        );
        const amount = (
          <span key="q" className="num">
            <Editable onEdit={edit("size")}>
              {o.quantity ? fmt(o.quantity, qtyDigits(m)) : o.quoteOrderQty ? `${fmt(o.quoteOrderQty, quoteDigits(m))} ${m?.quoteAsset ?? ""}` : "—"}
            </Editable>
          </span>
        );
        // Always in base, so a quote-sized order's -- whose amount column is in quote -- names it.
        const filled = (
          <span key="f" className="num">{fmt(o.filledQuantity, qtyDigits(m))}{o.quantity ? "" : ` ${m?.baseAsset ?? ""}`}</span>
        );
        return {
          key: o.orderId,
          cells: triggers
            ? [...first, <Editable key="tr" onEdit={edit("stopPrice")}><TriggerCell order={o} market={m} /></Editable>, price, amount, cancelCell(o)]
            : [...first, price, amount, filled, <span key="tot" className="num">{orderTotal(o, m)}</span>, cancelCell(o)],
        };
      })}
      notice={<>
        <FormError error={cancel.error ?? cancelAll.error} />
        {amendingOrder && amendingMarket && <AmendOrder order={amendingOrder} market={amendingMarket} account={sub === "lead" ? sub : undefined} onClose={() => setAmending(null)} />}
        {confirming && cancelable.length > 0 && (
          <ConfirmDialog title={t("orders.cancelAllTitle")} message={t("orders.cancelAllConfirm")} pending={cancelAll.isPending}
                         onConfirm={confirmCancelAll} onClose={() => setConfirming(false)} />
        )}
      </>}
    />
    </div>
  );
}

export function OrderHistory({ lockSymbol, compact = false, sub }: { lockSymbol?: string; compact?: boolean; sub?: Sub } = {}) {
  const t = useT();
  const tOpt = useTOptional();
  const time = useTime();
  const markets = useMarkets();
  const today = useToday();
  // Compact opens on the last day, as the exchanges' bottom panels do.
  const initial = () => ({ range: lastDays(compact ? 1 : 7, today), symbol: lockSymbol ?? "", side: "" as SideValue, timeField: "CREATED_AT" as NonNullable<HistoryFilter["timeField"]> });
  const [f, setF] = useState(initial);
  const filter = historyFilter(f.range, f.symbol, f.side, f.timeField);
  const [page, setPage] = useState(0);
  const size = pageSize(compact);
  const q = useQuery({
    queryKey: ["orders", "history", filter, page, size, subKey(sub)],
    queryFn: ({ signal }) => fetchOrderHistory({ page, size }, filter, signal, sub ?? undefined),
    // The old page stays while the next loads, rather than the table blinking empty.
    placeholderData: keepPreviousData,
    enabled: !nothingToRead(sub),
    initialData: nothingToRead(sub) ? [] : undefined,
  });
  const rows = q.data ?? [];
  // A new filter starts from the first page: page 4 of a wider list is nowhere in a narrower one.
  const refilter = (next: typeof f) => { setF(next); setPage(0); };
  return (
    <div className="flex flex-col gap-4">
    <FilterBar compact={compact} onReset={() => refilter(initial())}>
      <DateRangeFilter value={f.range} onChange={(range) => refilter({ ...f, range })} today={today} compact={compact} />
      {!lockSymbol && <PairPicker value={f.symbol} onChange={(symbol) => refilter({ ...f, symbol })} markets={sorted(markets)} />}
      <SideFilter value={f.side} onChange={(side) => refilter({ ...f, side })} compact={compact} />
      <SelectFilter label={t("filters.time")} value={f.timeField} onChange={(timeField) => refilter({ ...f, timeField })} compact={compact}
                    options={[{ value: "CREATED_AT", label: t("filters.orderTime") }, { value: "UPDATED_AT", label: t("filters.updateTime") }]} />
    </FilterBar>
    <Table
      compact={compact}
      loading={q.isPending} error={q.error} empty={t("orders.emptyFiltered")}
      head={[t("orders.time"), t("orders.pair"), t("orders.type"), t("orders.side"), t("orders.avgPrice"), t("orders.price"), t("orders.executed"), t("orders.amount"), t("orders.total"), t("orders.trigger"), t("orders.status")]}
      right={[4, 5, 6, 7, 8]}
      rows={rows.map((o: Order) => {
        const m = markets.get(o.symbol);
        return {
          key: o.orderId,
          cells: [
            <span key="t" className="num text-muted">{time(f.timeField === "UPDATED_AT" ? o.updatedAt : o.createdAt)}</span>,
            <Pair key="p" symbol={o.symbol} market={m} />,
            tOpt(`orderType.${o.orderType}`) ?? o.orderType,
            <Side key="s" side={o.side} />,
            <span key="a" className="num">{o.averageFillPrice ? fmt(o.averageFillPrice, pxDigits(m)) : "—"}</span>,
            <span key="px" className="num">{o.price ? fmt(o.price, pxDigits(m)) : t("orders.market")}</span>,
            <span key="e" className="num">{fmt(o.filledQuantity, qtyDigits(m))}</span>,
            <span key="q" className="num">{o.quantity ? fmt(o.quantity, qtyDigits(m)) : "—"}</span>,
            <span key="tot" className="num">{fmt(o.cumulativeQuoteQuantity, quoteDigits(m))}</span>,
            <TriggerCell key="tr" order={o} market={m} />,
            <span key="st" className={o.status === "FILLED" ? "text-text" : "text-muted"}>{tOpt(`orderStatus.${o.status}`) ?? o.status}</span>,
          ],
        };
      })}
      pager={<Pager current={page} hasNext={rows.length === size} onPage={setPage} />}
    />
    </div>
  );
}

export function TradeHistory({ lockSymbol, compact = false, sub }: { lockSymbol?: string; compact?: boolean; sub?: Sub } = {}) {
  const t = useT();
  const tOpt = useTOptional();
  const time = useTime();
  const markets = useMarkets();
  const today = useToday();
  const initial = () => ({ range: lastDays(compact ? 1 : 7, today), symbol: lockSymbol ?? "", side: "" as SideValue });
  const [f, setF] = useState(initial);
  const filter = historyFilter(f.range, f.symbol, f.side);
  const [page, setPage] = useState(0);
  const size = pageSize(compact);
  const q = useQuery({
    queryKey: ["trades", filter, page, size, subKey(sub)],
    queryFn: ({ signal }) => fetchMyTrades({ page, size }, filter, signal, sub ?? undefined),
    placeholderData: keepPreviousData,
    enabled: !nothingToRead(sub),
    initialData: nothingToRead(sub) ? [] : undefined,
  });
  const rows = q.data ?? [];
  // A new filter starts from the first page: page 4 of a wider list is nowhere in a narrower one.
  const refilter = (next: typeof f) => { setF(next); setPage(0); };
  return (
    <div className="flex flex-col gap-4">
    <FilterBar compact={compact} onReset={() => refilter(initial())}>
      <DateRangeFilter value={f.range} onChange={(range) => refilter({ ...f, range })} today={today} compact={compact} />
      {!lockSymbol && <PairPicker value={f.symbol} onChange={(symbol) => refilter({ ...f, symbol })} markets={sorted(markets)} />}
      <SideFilter value={f.side} onChange={(side) => refilter({ ...f, side })} compact={compact} />
    </FilterBar>
    <Table
      compact={compact}
      loading={q.isPending} error={q.error} empty={t("orders.emptyTradesFiltered")}
      head={[t("orders.time"), t("orders.pair"), t("orders.type"), t("orders.side"), t("orders.price"), t("orders.executed"), t("orders.fee"), t("orders.role"), t("orders.total")]}
      right={[4, 5, 6, 8]}
      rows={rows.map((tr: MyTrade) => {
        const m = markets.get(tr.symbol);
        return {
          key: tr.tradeId,
          cells: [
            <span key="t" className="num text-muted">{time(tr.time)}</span>,
            <Pair key="p" symbol={tr.symbol} market={m} />,
            tr.orderType ? tOpt(`orderType.${tr.orderType}`) ?? tr.orderType : "—",
            <Side key="s" side={tr.side} />,
            <span key="px" className="num">{fmt(tr.price, pxDigits(m))}</span>,
            <span key="q" className="num">{fmt(tr.quantity, qtyDigits(m))}</span>,
            <span key="f" className="num text-muted">{tr.fee} {tr.feeAsset}</span>,
            <span key="r" className="text-muted">{tr.isMaker ? t("orders.maker") : t("orders.taker")}</span>,
            <span key="tot" className="num">{fmt(tr.quoteQuantity, quoteDigits(m))} {m?.quoteAsset ?? ""}</span>,
          ],
        };
      })}
      pager={<Pager current={page} hasNext={rows.length === size} onPage={setPage} />}
    />
    </div>
  );
}

/** Price × amount for a limit order; the quote budget for a quote-sized market order. */
function orderTotal(o: Order, m?: MarketSymbol): string {
  if (o.price && o.quantity) return fmt(multiply(o.price, o.quantity), quoteDigits(m));
  return o.quoteOrderQty ? fmt(o.quoteOrderQty, quoteDigits(m)) : "—";
}

function TriggerCell({ order, market }: { order: Order; market?: MarketSymbol }) {
  const t = useT();
  const trigger = triggerOf(order);
  if (!trigger) return <span className="text-dim">—</span>;
  const px = (p: string) => fmt(p, pxDigits(market));
  if (trigger.kind === "price") return <span className="num">{t("orders.lastPrice")} {trigger.op} {px(trigger.price)}</span>;
  return (
    <span className="num">
      {t("orders.trailing", { pct: trigger.percent })}
      {trigger.activation && <span className="text-muted"> · {t("orders.activatesAt", { price: px(trigger.activation) })}</span>}
    </span>
  );
}

function Pair({ symbol, market }: { symbol: string; market?: MarketSymbol }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className="font-medium">{market ? market.baseAsset : symbol}</span>
      {market && <span className="text-dim">/ {market.quoteAsset}</span>}
    </span>
  );
}

/**
 * A value with the pencil that edits it, the way the exchanges do it: the affordance sits on
 * the field it changes rather than in a column of its own. Shown only for a field this order
 * can actually amend (amendMath) and only while it is still amendable.
 */
function Editable({ children, onEdit }: { children: React.ReactNode; onEdit?: () => void }) {
  const t = useT();
  if (!onEdit) return <>{children}</>;
  return (
    <span className="group inline-flex items-center gap-1.5">
      {children}
      <button type="button" onClick={onEdit} aria-label={t("orders.amend")} title={t("orders.amend")}
              className="text-dim opacity-60 transition-opacity hover:text-text group-hover:opacity-100">
        <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1.5"
             strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M11.5 2.5a1.4 1.4 0 0 1 2 2L6 12l-3 1 1-3z" />
        </svg>
      </button>
    </span>
  );
}

function Side({ side }: { side: "BUY" | "SELL" }) {
  const t = useT();
  return <span className={side === "BUY" ? "text-up" : "text-down"}>{t(side === "BUY" ? "side.BUY" : "side.SELL")}</span>;
}

/** Flat, on the page (docs/STYLE.md "Surfaces"). */
function Table({ head, right, rows, loading, error, empty, pager, notice, compact = false }: {
  head: React.ReactNode[]; right: number[]; rows: { key: string; cells: React.ReactNode[] }[];
  loading: boolean; error: unknown; empty: string; notice?: React.ReactNode; compact?: boolean;
  pager?: React.ReactNode;
}) {
  const t = useT();
  const describe = useDescribe();
  if (error) return <p role="alert" className={`py-8 text-center text-muted ${compact ? "text-xs" : "text-base"}`}>{describe(error)}</p>;
  if (loading) return <p className={`py-8 text-center text-dim ${compact ? "text-xs" : "text-base"}`} aria-busy>{t("common.loading")}</p>;
  const align = (i: number) => (right.includes(i) ? "text-right" : "text-left");
  return (
    <div className="flex flex-col gap-3">
      {notice}
      <div className="overflow-x-auto">
        <table className={`w-full whitespace-nowrap ${compact ? "text-xs" : "text-base"}`}>
          <thead><tr className={`border-b border-border text-muted ${compact ? "text-xs" : "text-sm"}`}>
            {head.map((h, i) => <th key={i} className={`px-2 py-2 font-medium ${align(i)}`}>{h}</th>)}
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} className="border-b border-border transition-colors hover:bg-surface-raised">
                {r.cells.map((c, i) => <td key={i} className={`px-2 ${compact ? "py-1.5" : "py-3"} ${align(i)}`}>{c}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <Empty compact={compact}>{empty}</Empty>}
      </div>
      {pager}
    </div>
  );
}
