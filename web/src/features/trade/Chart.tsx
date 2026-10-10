"use client";

import { keepPreviousData, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { CandlestickSeries, ColorType, createChart, CrosshairMode, HistogramSeries, PriceScaleMode, type IChartApi, type ISeriesApi, type UTCTimestamp } from "lightweight-charts";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useDescribe } from "@/components/ui/FormError";
import { Menu } from "@/components/ui/Menu";
import { fetchCandles, type Candle, type CandleInterval, type MarketSymbol } from "@/features/markets/api";
import { useLocale, useT } from "@/lib/i18n";
import { percent, price } from "@/lib/format";
import { compare, isNegative, scaleOf, subtract } from "@/lib/money";
import { advance, applyKline, AVAILABLE, intervalLabel, INTERVALS, PINNED, prependPage, type Kline } from "./candles";
import { useMarketChannel, useMarketSocket } from "./MarketStream";

const LIMIT = 500;
type Range = "1D" | "5D" | "1M" | "3M" | "6M" | "1Y";
/** Remembered per browser, like the layout: the interval a viewer picked, and whether volume shows. */
const PREFS = "trade-chart";
type Prefs = { interval: CandleInterval; volume: boolean };
function loadPrefs(): Prefs {
  try {
    const p = JSON.parse(localStorage.getItem(PREFS) ?? "{}") as Partial<Prefs>;
    return { interval: INTERVALS.includes(p.interval as CandleInterval) ? (p.interval as CandleInterval) : "15m", volume: p.volume !== false };
  } catch { return { interval: "15m", volume: true }; }
}
function savePrefs(p: Prefs) { try { localStorage.setItem(PREFS, JSON.stringify(p)); } catch { /* a private window */ } }
const RANGE_MS: Record<Range, number> = { "1D": 864e5, "5D": 5 * 864e5, "1M": 30 * 864e5, "3M": 90 * 864e5, "6M": 180 * 864e5, "1Y": 365 * 864e5 };
/** A range shortcut also picks the interval that draws it at a sensible bar count (TradingView's own
 *  behaviour); 500 loaded bars must cover the range, or the shortcut can only fit what is loaded. */
const RANGE_INTERVAL: Record<Range, CandleInterval> = { "1D": "5m", "5D": "15m", "1M": "4h", "3M": "4h", "6M": "1d", "1Y": "1d" };
const sec = (c: Candle) => (Date.parse(c.openTime) / 1000) as UTCTimestamp;
const candleKey = (symbol: string, interval: CandleInterval) => ["candles", symbol, interval] as const;
/** One stable empty series, so effects keyed on it do not re-run every render before data arrives. */
const EMPTY: Candle[] = [];

/** The chart's colours come from the theme tokens, re-read when the theme changes. */
function tokens() {
  const s = getComputedStyle(document.documentElement);
  const v = (n: string) => s.getPropertyValue(n).trim();
  return { text: v("--text-muted"), up: v("--up"), down: v("--down"), upSoft: v("--up-soft"), downSoft: v("--down-soft"), border: v("--border-strong") };
}

/**
 * Candles and volume with lightweight-charts.
 *
 * The bars live in the query cache, one entry per interval, and stay current there: every loaded
 * interval keeps a kline subscription that folds frames into its entry ({@link KlineFeed}), the
 * shown one is advanced to the current bucket by a clock tick, and pages scrolled into are
 * prepended. So an interval fetches once, switching between intervals is a cache read with no
 * request, and a burst of clicks cannot trip the rate limit or leave a series belonging to the
 * wrong interval -- each entry is keyed by its own. A socket reconnect invalidates them all, since
 * frames may have been missed meanwhile.
 */
export function Chart({ market }: { market: MarketSymbol }) {
  const t = useT();
  const locale = useLocale();
  const describe = useDescribe();
  const queryClient = useQueryClient();
  const socket = useMarketSocket();
  const [prefs, setPrefs] = useState<Prefs>(loadPrefs);
  const interval = prefs.interval;
  const setPref = (patch: Partial<Prefs>) => setPrefs((p) => { const next = { ...p, ...patch }; savePrefs(next); return next; });
  const [hover, setHover] = useState<Candle | null>(null);
  const [log, setLog] = useState(false);
  const [clock, setClock] = useState("");
  const digits = scaleOf(market.priceTick);
  const box = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const candles = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volume = useRef<ISeriesApi<"Histogram"> | null>(null);
  const bySec = useRef(new Map<number, Candle>());

  const key = candleKey(market.symbol, interval);
  const history = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => fetchCandles(market.symbol, interval, LIMIT, signal),
    // Never stale on its own: the feed and the tick keep it current. A reconnect invalidates.
    staleTime: Infinity,
    // The previous interval's bars stay up while the next one loads, as every exchange's chart does.
    placeholderData: keepPreviousData,
  });
  const series = history.data ?? EMPTY;
  const settled = !history.isPlaceholderData && !!history.data;
  const seriesRef = useRef<Candle[]>([]);
  useEffect(() => { seriesRef.current = settled ? series : []; }, [series, settled]);

  useEffect(() => {
    const id = window.setInterval(() => {
      queryClient.setQueryData<Candle[]>(key, (old) => (old ? advance(old, Date.now(), interval) : undefined));
      setClock(new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZoneName: "short" }).format(new Date()));
    }, 1000);
    return () => window.clearInterval(id);
    // key is derived from symbol and interval, both listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryClient, market.symbol, interval, locale]);
  useEffect(() => {
    if (!socket) return;
    return socket.onReconnect(() => { void queryClient.invalidateQueries({ queryKey: ["candles", market.symbol] }); });
  }, [socket, queryClient, market.symbol]);

  // Create once; theme changes restyle it in place.
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const c = createChart(el, { autoSize: true, layout: { background: { type: ColorType.Solid, color: "transparent" }, attributionLogo: false }, crosshair: { mode: CrosshairMode.Normal }, timeScale: { timeVisible: true, secondsVisible: false, borderVisible: false }, rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.05, bottom: 0.25 } } });
    // No dotted last-price line: it would sit exactly on the flat bars of a quiet market and hide
    // them, leaving the real candles floating. The axis label still shows the last price.
    const cs = c.addSeries(CandlestickSeries, { priceFormat: { type: "price", precision: digits, minMove: Number(market.priceTick) }, priceLineVisible: false, lastValueVisible: true });
    const vs = c.addSeries(HistogramSeries, { priceFormat: { type: "volume" }, priceScaleId: "vol" });
    c.priceScale("vol").applyOptions({ scaleMargins: { top: 0.8, bottom: 0 }, visible: false });
    const restyle = () => {
      const k = tokens();
      c.applyOptions({ layout: { textColor: k.text }, grid: { vertLines: { visible: false }, horzLines: { visible: false } }, crosshair: { vertLine: { color: k.border, labelBackgroundColor: k.border }, horzLine: { color: k.border, labelBackgroundColor: k.border } } });
      // Borders on, so a flat bar (open == close) still draws as a visible line of the candle's width.
      cs.applyOptions({ upColor: k.up, downColor: k.down, wickUpColor: k.up, wickDownColor: k.down, borderVisible: true, borderUpColor: k.up, borderDownColor: k.down });
    };
    restyle();
    const mo = new MutationObserver(restyle); mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    c.subscribeCrosshairMove((p) => setHover(p.time ? bySec.current.get(p.time as number) ?? null : null));
    c.subscribeDblClick(() => setPrefs((p) => { const next = { ...p, volume: !p.volume }; savePrefs(next); return next; }));
    chart.current = c; candles.current = cs; volume.current = vs;
    return () => { mo.disconnect(); c.remove(); chart.current = null; };
  }, [digits, market.priceTick]);

  // Paging back: within 30 bars of the left edge, fetch the page ending just before the oldest bar
  // and prepend it to this interval's cache entry. One page in flight per interval, and none once a
  // short page has shown where history starts -- remembered per interval, like the bars themselves.
  const paging = useRef(new Map<string, { exhausted: boolean; inFlight: boolean }>());
  useEffect(() => {
    const c = chart.current;
    if (!c) return;
    const id = key.join(":");
    const state = paging.current.get(id) ?? { exhausted: false, inFlight: false };
    paging.current.set(id, state);
    const load = async () => {
      const oldest = seriesRef.current[0];
      if (!oldest || state.exhausted || state.inFlight) return;
      // A first page shorter than LIMIT is the whole history: nothing to ask for.
      if (seriesRef.current.length < LIMIT) { state.exhausted = true; return; }
      state.inFlight = true;
      try {
        const page = await fetchCandles(market.symbol, interval, LIMIT, undefined, new Date(Date.parse(oldest.openTime) - 1).toISOString());
        if (page.length < LIMIT) state.exhausted = true;
        if (page.length > 0) queryClient.setQueryData<Candle[]>(key, (old) => (old ? prependPage(old, page) : undefined));
      } catch {
        state.exhausted = true; // stop asking rather than hammer a failing endpoint; a reload starts over
      } finally {
        state.inFlight = false;
      }
    };
    const onRange = (r: { from: number } | null) => { if (r && r.from < 30) void load(); };
    c.timeScale().subscribeVisibleLogicalRangeChange(onRange);
    // The chart effect above is cleaned up first on unmount, and a removed chart refuses calls.
    return () => { try { c.timeScale().unsubscribeVisibleLogicalRangeChange(onRange); } catch { /* already removed */ } };
    // key is derived from symbol and interval, both listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryClient, market.symbol, interval]);

  // Full setData on every change: lightweight-charts diffs internally, and it keeps flat-fill and live updates on one path.
  useEffect(() => {
    if (!candles.current || !volume.current) return;
    const k = tokens();
    bySec.current = new Map(series.map((c) => [sec(c) as number, c]));
    candles.current.setData(series.map((c) => ({ time: sec(c), open: +c.open, high: +c.high, low: +c.low, close: +c.close })));
    // A young market has only a few bars; spread them across the pane rather than crushing them against the right edge.
    if (series.length > 0 && series.length < 60 && chart.current) {
      // Not fitContent: on two bars it makes each a slab a third of the pane wide (and it applies
      // lazily, so its spacing cannot be read back here). Spread the bars to at most 12px each.
      const ts = chart.current.timeScale();
      ts.applyOptions({ barSpacing: Math.min(12, Math.max(6, Math.floor(ts.width() / (series.length + 6)))), rightOffset: 4 });
      ts.scrollToRealTime();
    }
    volume.current.setData(series.map((c) => ({ time: sec(c), value: +c.volume, color: compare(c.close, c.open) < 0 ? k.downSoft : k.upSoft })));
  }, [series]);
  useEffect(() => { chart.current?.priceScale("right").applyOptions({ mode: log ? PriceScaleMode.Logarithmic : PriceScaleMode.Normal }); }, [log]);
  useEffect(() => {
    volume.current?.applyOptions({ visible: prefs.volume });
    chart.current?.priceScale("right").applyOptions({ scaleMargins: { top: 0.05, bottom: prefs.volume ? 0.25 : 0.05 } });
  }, [prefs.volume]);

  // A range shortcut switches the interval, then applies the window once that interval's bars are in.
  // A ref, not state: the window is applied to the chart, nothing renders from it.
  const pendingRange = useRef<Range | null>(null);
  // The range last chosen stays lit until an interval is picked by hand, so the row answers "what am I looking at".
  const [activeRange, setActiveRange] = useState<Range | null>(null);
  const showRange = (r: Range) => { pendingRange.current = r; setActiveRange(r); setPref({ interval: RANGE_INTERVAL[r] }); };
  const pickInterval = (i: CandleInterval) => { setActiveRange(null); setPref({ interval: i }); };
  useEffect(() => {
    const r = pendingRange.current;
    if (!r || !settled || !chart.current) return;
    const first = series[0]; const last = series[series.length - 1];
    if (!first || !last) return;
    pendingRange.current = null;
    const to = sec(last) as number; const from = to - RANGE_MS[r] / 1000;
    // A range reaching before the first bar would show a mostly empty pane; fit what exists instead.
    if (from <= (sec(first) as number)) chart.current.timeScale().fitContent();
    else chart.current.timeScale().setVisibleRange({ from: from as UTCTimestamp, to: to as UTCTimestamp });
  }, [settled, series]);
  const shown = hover ?? series[series.length - 1] ?? null;
  const change = shown ? subtract(shown.close, shown.open) : null;
  const tone = change === null ? "text-muted" : compare(change, "0") === 0 ? "text-muted" : isNegative(change) ? "text-down" : "text-up";
  const tab = (on: boolean) => `rounded px-2 py-1 text-xs ${on ? "bg-surface-raised text-text" : "text-muted hover:text-text"}`;
  const intervalButton = (i: CandleInterval, close?: () => void) => (
    <button key={i} type="button" aria-pressed={i === interval} className={tab(i === interval)} onClick={() => { pickInterval(i); close?.(); }}>{intervalLabel(i)}</button>
  );
  const grid = (list: CandleInterval[], close: () => void) => (
    <div className="grid grid-cols-4 gap-2">{list.map((i) => (
      <button key={i} type="button" aria-pressed={i === interval} onClick={() => { pickInterval(i); close(); }}
              className={`h-9 rounded-md border text-sm ${i === interval ? "border-border-strong bg-surface-raised text-text" : "border-border text-muted hover:text-text"}`}>{intervalLabel(i)}</button>
    ))}</div>
  );

  return (
    <div className="flex h-full flex-col">
      <LoadedKlineFeeds symbol={market.symbol} />
      <div className="flex h-10 shrink-0 items-center gap-1 border-b border-border px-2">
        {PINNED.map((i) => intervalButton(i))}
        {/* A chosen interval from behind the picker sits at the end of the row while it is in use. */}
        {!PINNED.includes(interval) && intervalButton(interval)}
        <Menu label={t("trade.moreIntervals")} align="left" trigger={(open) => (
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className={open ? "rotate-180" : ""}><path d="m6 9 6 6 6-6" /></svg>
        )}>
          {(close) => (
            <div className="flex w-80 flex-col gap-4 p-4">
              <div className="flex flex-col gap-2"><span className="text-sm text-muted">{t("trade.pinned")}</span>{grid(PINNED, close)}</div>
              <div className="flex flex-col gap-2"><span className="text-sm text-muted">{t("trade.available")}</span>{grid(AVAILABLE, close)}</div>
            </div>
          )}
        </Menu>
        <span className="ml-auto text-xs text-muted">{t("trade.lastPrice")}</span>
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-x-3 px-3 py-1.5 text-xs">
        <span className="font-medium text-text">{market.baseAsset}/{market.quoteAsset} · {intervalLabel(interval)}</span>
        {shown ? (["open", "high", "low", "close"] as const).map((f) => (
          <span key={f} className={`num ${tone}`}><span className="text-muted">{f[0].toUpperCase()}</span>{price(shown[f], digits)}</span>
        )) : <span className="text-dim">{t("trade.noTrades")}</span>}
        {change !== null && shown && <span className={`num ${tone}`}>{(isNegative(change) ? "" : "+") + price(change, digits)} ({percent(pct(change, shown.open))})</span>}
      </div>
      <div className="relative min-h-0 flex-1">
        <div ref={box} className="absolute inset-0" />
        {history.error && <p role="alert" className="absolute inset-0 flex items-center justify-center text-sm text-muted">{describe(history.error)}</p>}
        {history.data && series.length === 0 && <p className="absolute inset-0 flex items-center justify-center text-sm text-dim">{t("trade.noTrades")}</p>}
        {shown && prefs.volume && <span className="absolute top-1 left-3 text-xs text-muted">{t("trade.volume", { asset: market.baseAsset })} <span className={`num ${tone}`}>{price(shown.volume, scaleOf(market.qtyStep))}</span></span>}
      </div>
      <div className="flex h-8 shrink-0 items-center gap-1 border-t border-border px-2 text-xs">
        {(Object.keys(RANGE_MS) as Range[]).map((r) => <button key={r} type="button" aria-pressed={activeRange === r} className={tab(activeRange === r)} onClick={() => showRange(r)}>{r}</button>)}
        <span className="num ml-auto text-muted">{clock}</span>
        <button type="button" aria-pressed={log} className={`ml-2 ${tab(log)}`} onClick={() => setLog((v) => !v)}>log</button>
        <button type="button" className={tab(false)} onClick={() => chart.current?.timeScale().fitContent()}>auto</button>
      </div>
    </div>
  );
}

/** Which intervals of this symbol hold bars in the cache right now -- read straight off the query cache. */
function useLoadedIntervals(queryClient: QueryClient, symbol: string): CandleInterval[] {
  const snapshot = () => INTERVALS.filter((i) => queryClient.getQueryData(candleKey(symbol, i)) !== undefined).join(",");
  const joined = useSyncExternalStore((cb) => queryClient.getQueryCache().subscribe(cb), snapshot, () => "");
  return joined ? (joined.split(",") as CandleInterval[]) : [];
}

/** One kline subscription per interval that has bars in the cache, folding each frame into that entry. Not one
 *  per interval that exists: a feed for bars nobody has loaded would be fan-out for nothing. */
function LoadedKlineFeeds({ symbol }: { symbol: string }) {
  const queryClient = useQueryClient();
  const loaded = useLoadedIntervals(queryClient, symbol);
  return <>{loaded.map((i) => <KlineFeed key={i} symbol={symbol} interval={i} />)}</>;
}

function KlineFeed({ symbol, interval }: { symbol: string; interval: CandleInterval }) {
  const queryClient = useQueryClient();
  useMarketChannel<Kline>({ channel: "kline", symbol, interval }, (k) =>
    queryClient.setQueryData<Candle[]>(candleKey(symbol, interval), (old) => (old ? applyKline(old, k, interval) : undefined)));
  return null;
}

/** Percent change for display only -- parseFloat is allowed at render (AGENTS.md). */
function pct(change: string, open: string): string {
  const o = parseFloat(open);
  return o ? ((parseFloat(change) / o) * 100).toFixed(2) : "0.00";
}
