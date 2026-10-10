"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useDescribe } from "@/components/ui/FormError";
import { fetchDepth, fetchRecentTrades, type Depth, type MarketSymbol, type PublicTrade } from "@/features/markets/api";
import { useLocale, useT } from "@/lib/i18n";
import { price as fmt } from "@/lib/format";
import { scaleOf } from "@/lib/money";
import { bidShare, cumulative, lastDirection, prependTrade, type BookRow } from "./bookMath";
import { useMarketChannel } from "./MarketStream";

/** A side of the book, snapshot and frames alike: DepthBook.TOP_LEVELS, which is what the socket sends. */
const LEVELS = 50;
const TAPE = 60;
type View = "both" | "bids" | "asks";

/** The middle column: the book and the tape as tabs, a grouping stepper, three view buttons.
 *  With {@code only} set the layout owns the tab (narrow screens) and the bar here is not drawn. */
export function OrderBook({ market, only, onPickPrice, onBest }: { market: MarketSymbol; only?: "book" | "trades"; onPickPrice?: (price: string) => void; onBest?: (best: { bid: string | null; ask: string | null }) => void }) {
  const t = useT();
  const [ownTab, setTab] = useState<"book" | "trades">("book");
  const tab = only ?? ownTab;
  const [view, setView] = useState<View>("both");
  const groupings = market.depthGroupings;
  const [grouping, setGrouping] = useState<string | null>(groupings[0] ?? null);
  const gi = grouping ? groupings.indexOf(grouping) : -1;
  const step = (d: number) => { const n = groupings[gi + d]; if (n) setGrouping(n); };
  const iconBtn = (on: boolean) => `rounded p-1 ${on ? "text-text" : "text-dim hover:text-muted"}`;
  const bars = (asks: string, bids: string) => (
    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden><rect x="1" y="1" width="6" height="6" fill={asks} /><rect x="1" y="9" width="6" height="6" fill={bids} /><rect x="9" y="2" width="6" height="1.5" fill="currentColor" opacity=".5" /><rect x="9" y="6" width="6" height="1.5" fill="currentColor" opacity=".5" /><rect x="9" y="10" width="6" height="1.5" fill="currentColor" opacity=".5" /></svg>
  );

  return (
    <div className="flex h-full flex-col">
      {!only && <div className="flex h-10 shrink-0 items-center gap-4 border-b border-border px-3 text-sm">
        {(["book", "trades"] as const).map((x) => (
          <button key={x} type="button" role="tab" aria-selected={tab === x} onClick={() => setTab(x)}
                  className={`-mb-px h-10 border-b-2 ${tab === x ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>{t(x === "book" ? "trade.orderBook" : "trade.lastTrades")}</button>
        ))}
      </div>}
      {tab === "book" ? (
        <>
          <div className="flex h-9 shrink-0 items-center gap-1 px-2">
            <button type="button" aria-label={t("trade.viewBoth")} aria-pressed={view === "both"} className={iconBtn(view === "both")} onClick={() => setView("both")}>{bars("var(--down)", "var(--up)")}</button>
            <button type="button" aria-label={t("trade.viewBids")} aria-pressed={view === "bids"} className={iconBtn(view === "bids")} onClick={() => setView("bids")}>{bars("var(--up)", "var(--up)")}</button>
            <button type="button" aria-label={t("trade.viewAsks")} aria-pressed={view === "asks"} className={iconBtn(view === "asks")} onClick={() => setView("asks")}>{bars("var(--down)", "var(--down)")}</button>
            {groupings.length > 0 && (
              <div className="ml-auto flex items-center gap-1 text-xs">
                <button type="button" aria-label={t("trade.finerGrouping")} disabled={gi <= 0} onClick={() => step(-1)} className="rounded border border-border px-1.5 text-muted hover:text-text disabled:opacity-40">−</button>
                <select aria-label={t("trade.grouping")} value={grouping ?? ""} onChange={(e) => setGrouping(e.target.value)} className="num h-6 rounded border border-border bg-surface-raised px-1 text-xs text-text outline-none">
                  {groupings.map((g) => <option key={g} value={g}>{g}</option>)}
                </select>
                <button type="button" aria-label={t("trade.coarserGrouping")} disabled={gi < 0 || gi >= groupings.length - 1} onClick={() => step(1)} className="rounded border border-border px-1.5 text-muted hover:text-text disabled:opacity-40">+</button>
              </div>
            )}
          </div>
          <Book market={market} grouping={grouping} view={view} onPickPrice={onPickPrice} onBest={onBest} />
        </>
      ) : <Tape market={market} />}
    </div>
  );
}

function useTrades(symbol: string): { trades: PublicTrade[]; error: unknown } {
  const snapshot = useQuery({ queryKey: ["trades-tape", symbol], queryFn: ({ signal }) => fetchRecentTrades(symbol, TAPE, signal), staleTime: Infinity });
  const [live, setLive] = useState<PublicTrade[]>([]);
  useMarketChannel<PublicTrade>({ channel: "trade", symbol }, (tr) => setLive((l) => prependTrade(l, tr, TAPE)));
  // Live trades, then the snapshot's for what they do not cover; a trade in both is dropped by sequence.
  const seen = new Set(live.map((x) => x.sequence));
  const trades = [...live, ...(snapshot.data ?? []).filter((x) => !seen.has(x.sequence))].slice(0, TAPE);
  return { trades, error: snapshot.error };
}

function Book({ market, grouping, view, onPickPrice, onBest }: { market: MarketSymbol; grouping: string | null; view: View; onPickPrice?: (price: string) => void; onBest?: (best: { bid: string | null; ask: string | null }) => void }) {
  const t = useT();
  const describe = useDescribe();
  const snapshot = useQuery({ queryKey: ["depth", market.symbol, grouping], queryFn: ({ signal }) => fetchDepth(market.symbol, grouping, LEVELS, signal), staleTime: Infinity });
  const [live, setLive] = useState<Depth | null>(null);
  useMarketChannel<Depth>({ channel: "depth", symbol: market.symbol, grouping: grouping ?? undefined }, setLive);
  const { trades } = useTrades(market.symbol);
  // Frames are the whole book (AGENTS.md), so the newest one simply replaces the snapshot.
  const depth = live && live.symbol === market.symbol ? live : snapshot.data;
  const pd = scaleOf(grouping ?? market.priceTick); const qd = scaleOf(market.qtyStep);
  const asks = cumulative((depth?.asks ?? []).slice(0, LEVELS)); const bids = cumulative((depth?.bids ?? []).slice(0, LEVELS));
  const last = trades[0]; const dir = lastDirection(trades);
  const share = bidShare(depth?.bids ?? [], depth?.asks ?? []);
  // The form's "best" button and market-order estimate follow the top of the (ungrouped) book.
  const bestBid = depth?.bids[0]?.price ?? null; const bestAsk = depth?.asks[0]?.price ?? null;
  useEffect(() => { onBest?.({ bid: bestBid, ask: bestAsk }); }, [bestBid, bestAsk, onBest]);
  // Each half scrolls, so this is how many levels exist to scroll through, not how many fit.
  const rows = LEVELS;

  if (snapshot.error) return <p role="alert" className="p-6 text-center text-sm text-muted">{describe(snapshot.error)}</p>;
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="grid grid-cols-3 px-3 py-1 text-xs text-muted">
        <span>{t("trade.priceIn", { asset: market.quoteAsset })}</span><span className="text-right">{t("trade.amountIn", { asset: market.baseAsset })}</span>
        {/* No unit on Total: it is the running sum of Amount, whose unit is one column left,
            and naming it again is what pushed this row onto two lines in a narrow book. */}
        <span className="text-right">{t("trade.total")}</span>
      </div>
      {/* Showing both sides, the two halves are `flex-1 basis-0`, so the last price sits exactly
          in the middle however many levels each side happens to have -- a strip that drifts
          with the depth is one the eye has to hunt for on every glance. Each half scrolls on
          its own, away from the middle, so the touch is always the row against the strip. */}
      <div className="flex min-h-0 flex-1 flex-col">
        {view !== "bids" && (
          <div className="scroll-slim flex min-h-0 flex-1 basis-0 flex-col-reverse overflow-y-auto">
            {asks.length === 0 ? <Empty /> : <Side list={asks} side="ask" rows={rows} pd={pd} qd={qd} onPickPrice={onPickPrice} />}
          </div>
        )}
        <div className="flex shrink-0 items-center gap-2 border-y border-border px-3 py-1.5">
          <span className={`num text-base font-semibold ${dir === "down" ? "text-down" : dir === "up" ? "text-up" : "text-text"}`}>{last ? fmt(last.price, scaleOf(market.priceTick)) : "—"}</span>
          {dir !== "flat" && <span aria-label={t(dir === "up" ? "trade.priceUp" : "trade.priceDown")} className={dir === "up" ? "text-up" : "text-down"}>{dir === "up" ? "↑" : "↓"}</span>}
        </div>
        {view !== "asks" && (
          <div className="scroll-slim flex min-h-0 flex-1 basis-0 flex-col overflow-y-auto">
            {bids.length === 0 ? <Empty /> : <Side list={bids} side="bid" rows={rows} pd={pd} qd={qd} onPickPrice={onPickPrice} />}
          </div>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2 border-t border-border px-3 py-1.5 text-xs">
        <span className="rounded bg-up-soft px-1 text-up">B</span><span className="num text-up">{(share * 100).toFixed(2)}%</span>
        <div className="flex h-1 flex-1 overflow-hidden rounded bg-down-soft"><div className="h-full bg-up" style={{ width: `${share * 100}%` }} /></div>
        <span className="num text-down">{((1 - share) * 100).toFixed(2)}%</span><span className="rounded bg-down-soft px-1 text-down">S</span>
      </div>
    </div>
  );
}

/** Said on the side that is empty, not in the middle, where the last price already sits. */
function Empty() {
  const t = useT();
  return <p className="flex flex-1 items-center justify-center px-3 text-center text-xs text-dim">{t("trade.emptyBook")}</p>;
}

/**
 * Rows in book order, best price first, laid out bottom-up for the asks so the best one sits
 * against the last price. `flex-col-reverse` rather than reversing the array, because the
 * scrolling pane above uses it too and for a second reason: a reversed column rests its
 * scroll at the visual bottom, so overflow hides the far asks instead of the touch.
 */
function Side({ list, side, rows, pd, qd, onPickPrice }: {
  list: BookRow[]; side: "ask" | "bid"; rows: number; pd: number; qd: number; onPickPrice?: (price: string) => void;
}) {
  return (
    <div className={`flex ${side === "ask" ? "flex-col-reverse" : "flex-col"}`}>
      {list.slice(0, rows).map((r) => (
        <button key={r.price} type="button" onClick={() => onPickPrice?.(r.price)}
                className="relative grid h-[26px] grid-cols-3 items-center px-3 text-left text-xs hover:bg-surface-raised">
          <span aria-hidden className="absolute inset-y-0 right-0" style={{ width: `${Math.round(r.share * 100)}%`, background: side === "ask" ? "var(--down-soft)" : "var(--up-soft)" }} />
          <span className={`num relative ${side === "ask" ? "text-down" : "text-up"}`}>{fmt(r.price, pd)}</span>
          <span className="num relative text-right">{fmt(r.quantity, qd)}</span>
          <span className="num relative text-right text-muted">{fmt(r.total, qd)}</span>
        </button>
      ))}
    </div>
  );
}

function Tape({ market }: { market: MarketSymbol }) {
  const t = useT();
  const locale = useLocale();
  const describe = useDescribe();
  const { trades, error } = useTrades(market.symbol);
  const pd = scaleOf(market.priceTick); const qd = scaleOf(market.qtyStep);
  const time = new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
  if (error) return <p role="alert" className="p-6 text-center text-sm text-muted">{describe(error)}</p>;
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="grid grid-cols-3 px-3 py-1 text-xs text-muted">
        <span>{t("trade.priceIn", { asset: market.quoteAsset })}</span><span className="text-right">{t("trade.amountIn", { asset: market.baseAsset })}</span><span className="text-right">{t("trade.time")}</span>
      </div>
      <div className="scroll-slim min-h-0 flex-1 overflow-y-auto">
        {trades.map((tr) => (
          <div key={tr.sequence} className="grid h-[26px] grid-cols-3 items-center px-3 text-xs">
            <span className={`num ${tr.takerSide === "BUY" ? "text-up" : "text-down"}`}>{fmt(tr.price, pd)}</span>
            <span className="num text-right">{fmt(tr.quantity, qd)}</span>
            <span className="num text-right text-muted">{time.format(new Date(tr.time))}</span>
          </div>
        ))}
        {trades.length === 0 && <p className="py-8 text-center text-xs text-dim">{t("trade.noTrades")}</p>}
      </div>
    </div>
  );
}
