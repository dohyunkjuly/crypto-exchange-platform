"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useT } from "@/lib/i18n";
import { clampLayout, DIVIDER, dragColumn, loadLayout, PANEL_HEIGHT, saveLayout, type Layout } from "./layout";

const WIDE = "(min-width: 1280px)";
const subscribeWide = (cb: () => void) => { const m = matchMedia(WIDE); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); };
function useWide() { return useSyncExternalStore(subscribeWide, () => matchMedia(WIDE).matches, () => true); }

/**
 * The workspace: chart | order book | trade form, with the account panel under them.
 *
 * <p>The chart row is sized to the viewport, so the three regions own the screen and the
 * account panel begins where the fold is -- its tab strip shows, its tables are read by
 * scrolling the page. Depth is worth more screen than a list of your own orders, and every
 * exchange makes the same trade. Dragging the horizontal divider pulls the panel up.
 *
 * <p>Sizes persist per browser (localStorage, docs/STYLE.md). Below 1280px the regions stack: the strip, then Chart |
 * Order book | Trades as tabs, then the form, then the account panel. The tabs hide rather than
 * unmount, so the chart keeps its scroll and the book its subscription while another tab shows.
 */
export type NarrowTab = "chart" | "book" | "trades";

export function TradeLayout({ strip, chart, book, form, panel }: Record<"strip" | "chart" | "form" | "panel", React.ReactNode> & {
  /** Called with the tab a narrow screen shows; undefined on a wide one, where the book carries its own tabs. */
  book: (only?: "book" | "trades") => React.ReactNode;
}) {
  const t = useT();
  const wide = useWide();
  const [narrowTab, setNarrowTab] = useState<NarrowTab>("chart");
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 1600, height: 900 });
  const [layout, setLayout] = useState<Layout>(loadLayout);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setSize({ width: e.contentRect.width, height: e.contentRect.height }));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const l = clampLayout(layout, size.width, size.height);

  const drag = (apply: (start: Layout, dx: number, dy: number) => Layout) => (e: React.PointerEvent<HTMLDivElement>) => {
    const start = l; const x0 = e.clientX; const y0 = e.clientY;
    const target = e.currentTarget; target.setPointerCapture(e.pointerId);
    let latest = start;
    const move = (ev: PointerEvent) => { latest = apply(start, ev.clientX - x0, ev.clientY - y0); setLayout(latest); };
    const up = () => { target.removeEventListener("pointermove", move); target.removeEventListener("pointerup", up); saveLayout(clampLayout(latest, size.width, size.height)); };
    target.addEventListener("pointermove", move); target.addEventListener("pointerup", up);
  };
  const nudge = (apply: (start: Layout, step: number) => Layout) => (e: React.KeyboardEvent) => {
    const step = e.key === "ArrowLeft" || e.key === "ArrowUp" ? -16 : e.key === "ArrowRight" || e.key === "ArrowDown" ? 16 : 0;
    if (!step) return;
    e.preventDefault();
    const next = clampLayout(apply(l, step), size.width, size.height); setLayout(next); saveLayout(next);
  };
  const handle = "bg-border transition-colors hover:bg-border-strong focus-visible:bg-border-strong outline-none";

  if (!wide) {
    const tabs: NarrowTab[] = ["chart", "book", "trades"];
    return (
      <div className="flex flex-col">
        {strip}
        <div role="tablist" className="flex h-11 shrink-0 items-center gap-5 border-b border-border px-4 text-sm">
          {tabs.map((x) => (
            <button key={x} type="button" role="tab" aria-selected={narrowTab === x} onClick={() => setNarrowTab(x)}
                    className={`-mb-px h-11 border-b-2 ${narrowTab === x ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>
              {t(x === "chart" ? "trade.chart" : x === "book" ? "trade.orderBook" : "trade.trades")}
            </button>
          ))}
        </div>
        {/* A definite height: the chart canvas fills its box absolutely, so an auto-height box would give it nothing. */}
        <div className="h-[60vh] min-h-[420px]" hidden={narrowTab !== "chart"}>{chart}</div>
        <div className="flex h-[60vh] min-h-[420px] flex-col" hidden={narrowTab === "chart"}>{book(narrowTab === "trades" ? "trades" : "book")}</div>
        <div className="border-t border-border">{form}</div>
        <div className="flex h-[28rem] flex-col border-t border-border">{panel}</div>
      </div>
    );
  }
  // The header is 3rem and the market strip 3.5rem; what is left of the viewport belongs to
  // the chart row, less whatever the panel is peeking by.
  const chartRow = `calc(100dvh - 6.5rem - ${l.panel + DIVIDER}px)`;
  return (
    <div className="flex flex-col">
      {strip}
      <div ref={box} className="grid min-h-0" style={{ height: chartRow, gridTemplateColumns: `minmax(0,1fr) ${DIVIDER}px ${l.book}px ${DIVIDER}px ${l.form}px` }}>
        <div className="min-h-0 min-w-0 overflow-hidden">{chart}</div>
        <div role="separator" aria-orientation="vertical" aria-label={t("trade.resize")} tabIndex={0} className={`${handle} cursor-col-resize`}
             onPointerDown={drag((s, dx) => dragColumn(s, "chart-book", dx))} onKeyDown={nudge((s, d) => dragColumn(s, "chart-book", d))} />
        <div className="min-h-0 min-w-0 overflow-hidden">{book()}</div>
        <div role="separator" aria-orientation="vertical" aria-label={t("trade.resize")} tabIndex={0} className={`${handle} cursor-col-resize`}
             onPointerDown={drag((s, dx) => dragColumn(s, "book-form", dx))} onKeyDown={nudge((s, d) => dragColumn(s, "book-form", d))} />
        <div className="scroll-slim min-h-0 min-w-0 overflow-y-auto">{form}</div>
      </div>
      <div role="separator" aria-orientation="horizontal" aria-label={t("trade.resize")} tabIndex={0} className={`${handle} h-1 cursor-row-resize`}
           onPointerDown={drag((s, _dx, dy) => ({ ...s, panel: s.panel - dy }))} onKeyDown={nudge((s, d) => ({ ...s, panel: s.panel - d }))} />
      {/* Definite, so the panel's own tables can scroll inside it rather than growing the page. */}
      <div className="min-h-0 min-w-0 overflow-hidden" style={{ height: PANEL_HEIGHT }}>{panel}</div>
    </div>
  );
}
