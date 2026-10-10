import { describe, expect, it } from "vitest";
import { clampLayout, DEFAULT_LAYOUT, dragColumn, MIN, PANEL_HEIGHT, PANEL_PEEK } from "./layout";

describe("trade layout", () => {
  it("keeps the defaults on a wide screen", () => {
    expect(clampLayout(DEFAULT_LAYOUT, 1600, 900)).toEqual(DEFAULT_LAYOUT);
  });
  it("never lets a column drop below its minimum, whichever way the divider is dragged", () => {
    const l = clampLayout(dragColumn(DEFAULT_LAYOUT, "chart-book", 900), 1600, 900);
    expect(l.book).toBe(MIN.book);
    const r = clampLayout(dragColumn(DEFAULT_LAYOUT, "book-form", -500), 1600, 900);
    expect(r.book).toBe(MIN.book);
  });
  it("gives the chart its minimum before the book grows past what fits", () => {
    const l = clampLayout({ book: 900, form: 360, panel: 240 }, 1300, 900);
    expect(1300 - 8 - l.book - l.form).toBeGreaterThanOrEqual(MIN.chart);
  });
  it("moves width between the two columns beside a divider only", () => {
    const l = dragColumn(DEFAULT_LAYOUT, "book-form", 40);
    expect(l.book).toBe(500); expect(l.form).toBe(320); expect(l.panel).toBe(PANEL_PEEK);
    expect(dragColumn(DEFAULT_LAYOUT, "chart-book", 40).book).toBe(420);
  });
  it("shows only the account panel's tab strip by default, leaving the rest below the fold", () => {
    expect(DEFAULT_LAYOUT.panel).toBe(PANEL_PEEK);
  });
  it("never peeks by more than the panel actually is", () => {
    expect(clampLayout({ ...DEFAULT_LAYOUT, panel: 2000 }, 1600, 2000).panel).toBe(PANEL_HEIGHT);
  });
  it("never gives the panel more than half the screen, whatever it is dragged to", () => {
    expect(clampLayout({ ...DEFAULT_LAYOUT, panel: 2000 }, 1600, 600).panel).toBe(300);
  });
  it("keeps the tab strip visible however far the divider is dragged down", () => {
    expect(clampLayout({ ...DEFAULT_LAYOUT, panel: -500 }, 1600, 900).panel).toBe(PANEL_PEEK);
  });
});
