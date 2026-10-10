"use client";

import { useState } from "react";
import { Empty } from "@/components/ui/Empty";
import { useT } from "@/lib/i18n";
import type { LeaderStats } from "./api";
import { Choice, PERIODS, Percent, type Period } from "./Leaders";
import { ReturnChart } from "./ReturnChart";

export const card = "flex flex-col gap-4 rounded-xl border border-border p-5";

/** One line of a figures card: a label on the left, its number on the right. */
export const row = (label: string, value: React.ReactNode) => (
  <div key={label} className="flex items-center justify-between gap-4 text-base"><span className="text-muted">{label}</span><span className="num">{value}</span></div>
);

/** An amount in the copy asset, grouped; zero when there is none yet. */
export const amount = (value: string | null | undefined, asset: string) =>
  `${Number(value ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 })} ${asset}`;

/**
 * The nightly figures as every page shows them -- the public profile and the leader's own
 * console draw the same cards, so a leader sees exactly what a follower sees.
 */
/** `children`: further rows under the figures, as the public page adds the account's own. */
export function PerformanceCard({ stats: s, children }: { stats: LeaderStats | null; children?: React.ReactNode }) {
  const t = useT();
  const [period, setPeriod] = useState<Period>("days30");
  const w = s?.[period];
  return (
    <section className={card} aria-label={t("copy.performance")}>
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">{t("copy.performance")}</h2>
        <Choice label={t("copy.period")} value={period} onChange={setPeriod} options={PERIODS.map((p) => ({ value: p, label: t(`copyPeriod.${p}`) }))} />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div><div className="text-sm text-muted">{t("copySort.return")}</div><div className="text-2xl font-semibold"><Percent value={w?.return ?? null} signed /></div></div>
        <div className="text-right"><div className="text-sm text-muted">{t("copySort.pnl")}</div>
          <div className={`num text-2xl font-semibold ${Number(w?.pnl ?? 0) === 0 ? "" : Number(w?.pnl) > 0 ? "text-up" : "text-down"}`}>{`${Number(w?.pnl ?? 0) > 0 ? "+" : ""}${Number(w?.pnl ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 })}`}</div></div>
      </div>
      <div className="flex flex-col gap-2 border-t border-border pt-4">
        {row(t("copySort.maxDrawdown"), <Percent value={w?.maxDrawdown ?? null} />)}
        {row(t("copy.successRate"), <Percent value={s?.copySuccessRate ?? null} />)}
        {children}
      </div>
    </section>
  );
}

/** Thirty days at 0%, the chart a leader with no figures yet draws. */
function flatMonth(): { day: string; value: string }[] {
  const today = new Date();
  const day = (back: number) => new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - back))
    .toISOString().slice(0, 10);
  return [{ day: day(29), value: "0" }, { day: day(0), value: "0" }];
}

/** `empty`: what a chart with nothing to draw shows -- a flat line, or, on the public page, words. */
export function ReturnChartCard({ stats: s, empty = "flat" }: { stats: LeaderStats | null; empty?: "flat" | "words" }) {
  const t = useT();
  return (
    <section className={card} aria-label={t("copy.returnChart")}>
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">{t("copySort.return")}</h2>
        <span className="text-sm text-muted">{t("copyPeriod.days30")}</span>
      </div>
      {s && s.series.length >= 2 ? <ReturnChart points={s.series} label={t("copy.sparkline")} />
        : empty === "words" ? <Empty>{t("copy.noFigures")}</Empty>
        : <ReturnChart points={flatMonth()} label={t("copy.sparkline")} />}
    </section>
  );
}
