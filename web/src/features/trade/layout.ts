/**
 * Column widths, and how much of the account panel shows above the fold, in px.
 *
 * `panel` is a peek, not a height. The panel itself is `PANEL_HEIGHT` tall and sits below the
 * chart in the page, so the chart and the book get the screen and the panel's tables are read
 * by scrolling -- which is what every exchange does, because depth is worth more screen than
 * a table of your own orders. The default shows its tab strip and nothing else.
 */
export type Layout = { book: number; form: number; panel: number };

/** The panel's own height once scrolled to. Definite, so its tables can scroll inside it. */
export const PANEL_HEIGHT = 420;
/** The tab strip, and so the least that can peek. */
export const PANEL_PEEK = 40;

export const DEFAULT_LAYOUT: Layout = { book: 460, form: 360, panel: PANEL_PEEK };
export const MIN = { chart: 480, book: 260, form: 300, panel: PANEL_PEEK } as const;
/** Two dividers of this width sit between the columns; one above the panel. */
export const DIVIDER = 4;

/** Keeps every region at or above its minimum inside a container of the given size. When the
 *  container is too narrow for all three minimums, the chart gives way first. */
export function clampLayout(l: Layout, width: number, height: number): Layout {
  const form = Math.max(MIN.form, Math.min(l.form, width - 2 * DIVIDER - MIN.book - MIN.chart));
  const book = Math.max(MIN.book, Math.min(l.book, width - 2 * DIVIDER - form - MIN.chart));
  // Never more than the panel actually is, and never more than half the screen: past that the
  // chart is the thing you have to scroll for.
  const panel = Math.max(MIN.panel, Math.min(l.panel, PANEL_HEIGHT, Math.floor(height / 2)));
  return { book: Math.max(MIN.book, book), form: Math.max(MIN.form, form), panel };
}

/** A drag on the chart|book divider moves width between those two; on book|form, between those. */
export function dragColumn(l: Layout, divider: "chart-book" | "book-form", dx: number): Layout {
  if (divider === "chart-book") return { ...l, book: l.book - dx };
  return { ...l, book: l.book + dx, form: l.form - dx };
}

// Bumped when `panel` stopped meaning a height and started meaning a peek: a stored 240 would
// otherwise come back as a 240px peek and quietly undo the change for anyone who had dragged it.
const KEY = "trade-layout-2";
export function loadLayout(): Layout {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return DEFAULT_LAYOUT;
    const parsed = JSON.parse(raw) as Partial<Layout>;
    return { ...DEFAULT_LAYOUT, ...Object.fromEntries(Object.entries(parsed).filter(([, v]) => typeof v === "number" && Number.isFinite(v))) };
  } catch {
    return DEFAULT_LAYOUT;
  }
}
export function saveLayout(l: Layout) {
  try { localStorage.setItem(KEY, JSON.stringify(l)); } catch { /* a private window; the layout just does not persist */ }
}
