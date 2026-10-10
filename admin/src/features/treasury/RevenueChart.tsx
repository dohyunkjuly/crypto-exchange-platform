"use client";

import { useEffect, useId, useRef, useState } from "react";
import { AdminApiError } from "@/lib/api";
import { amount } from "@/lib/money";
import { fetchRevenue, type Day } from "./api";

const WIDTH = 900;
const HEIGHT = 140;
const PAD = { top: 8, right: 4, bottom: 22, left: 4 };

/**
 * Fees per UTC day for one asset, the last 30 days. Answers "is today normal", which a table
 * cannot; the table beside it answers "how much".
 *
 * <p>One series, so no legend: the asset's own name is the title. Columns, not a line -- each
 * day is a discrete total, not a reading between two points. Text wears text tokens and the
 * marks wear one hue; a value is read from the tooltip or the table, never off a colour.
 *
 * <p>{@code fill} sizes the drawing to whatever height its flex parent gives it, so the chart's
 * card can stand as tall as the table beside it. The SVG is drawn at the measured pixel size
 * rather than stretched, so text and bar corners keep their shape.
 */
export function RevenueChart({ asset, decimals, days: DAYS = 30, fill = false }: {
  asset: string; decimals: number; days?: number; fill?: boolean;
}) {
  const [days, setDays] = useState<Day[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [focus, setFocus] = useState<number | null>(null);
  const [box, setBox] = useState({ width: WIDTH, height: HEIGHT });
  const plotRef = useRef<HTMLDivElement>(null);
  const titleId = useId();

  useEffect(() => {
    if (!fill || !plotRef.current || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      if (width > 0 && height > 0) setBox({ width, height: Math.max(height, HEIGHT) });
    });
    observer.observe(plotRef.current);
    return () => observer.disconnect();
  }, [fill, days]);

  useEffect(() => {
    let cancelled = false;
    fetchRevenue(asset, DAYS)
      .then((d) => { if (!cancelled) setDays(d); })
      .catch((e: unknown) => {
        if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setError(e.message);
      });
    return () => { cancelled = true; };
  }, [asset, DAYS]);

  if (error) return <p role="alert" className="text-sm text-danger">{error}</p>;
  if (!days) return <div className="h-[140px] rounded-md bg-bg" aria-busy />;

  // Scaled as strings would be exact but pointless here: the bar height is pixels, the
  // number the reader takes away comes from the tooltip, which shows the string untouched.
  const values = days.map((d) => Number(d.fees));
  const max = Math.max(...values, 0);
  const { width, height } = fill ? box : { width: WIDTH, height: HEIGHT };
  const plotW = width - PAD.left - PAD.right;
  const plotH = height - PAD.top - PAD.bottom;
  const slot = plotW / days.length;
  const bar = Math.min(24, slot - 2);
  const y = (v: number) => (max === 0 ? plotH : plotH - (v / max) * plotH);
  const focused = focus === null ? null : days[focus];

  return (
    <figure className={`relative ${fill ? "flex flex-1 flex-col min-h-[140px]" : ""}`} aria-labelledby={titleId}>
      <figcaption id={titleId} className="mb-2 flex items-baseline justify-between text-sm">
        <span className="text-muted">{asset} · last {DAYS} days · UTC</span>
        <span className="font-mono text-muted">peak {amount(String(max), decimals)}</span>
      </figcaption>

      <div ref={plotRef} className={fill ? "flex-1 min-h-0" : ""}>
      <svg viewBox={`0 0 ${width} ${height}`} className={`block w-full ${fill ? "h-full" : ""}`} role="img"
           aria-label={`${asset} fees per day for the last ${DAYS} days`}
           onPointerLeave={() => setFocus(null)}>
        <g transform={`translate(${PAD.left},${PAD.top})`}>
          {/* Baseline only: a grid would be louder than thirty small bars. */}
          <line x1={0} x2={plotW} y1={plotH} y2={plotH} stroke="var(--border)" />
          {days.map((d, i) => {
            const h = plotH - y(values[i]);
            const x = i * slot + (slot - bar) / 2;
            const on = focus === i;
            return (
              <g key={d.day}>
                {/* The whole slot is the hit target, not just the bar, so a quiet day is still hoverable. */}
                <rect x={i * slot} y={0} width={slot} height={plotH} fill="transparent"
                      onPointerMove={() => setFocus(i)} onFocus={() => setFocus(i)} tabIndex={0}
                      aria-label={`${d.day}: ${amount(d.fees, decimals)} ${asset}`} />
                {h > 0 && (
                  <path d={roundedTop(x, y(values[i]), bar, h, 4)} pointerEvents="none"
                        fill={on ? "var(--accent)" : "var(--accent-muted)"} />
                )}
              </g>
            );
          })}
          {days.map((d, i) => (i % Math.ceil(days.length / 5) === 0 || i === days.length - 1) && (
            <text key={d.day} x={i * slot + slot / 2} y={plotH + 15} textAnchor="middle"
                  fill="var(--text-muted)" fontSize={10} fontFamily="var(--font-mono)">
              {d.day.slice(5)}
            </text>
          ))}
        </g>
      </svg>
      </div>

      {focused && focus !== null && (
        <div role="status" aria-live="polite"
             className="pointer-events-none absolute top-6 rounded-md border border-border bg-surface px-2.5 py-1.5 text-[12px] shadow-[0_4px_12px_rgba(140,149,159,0.25)]"
             style={{ left: `${((focus + 0.5) / days.length) * 100}%`, transform: "translateX(-50%)" }}>
          <div className="font-mono text-text">{amount(focused.fees, decimals)} {asset}</div>
          <div className="text-muted">{focused.day} · rebates {amount(focused.rebates, decimals)}</div>
        </div>
      )}
    </figure>
  );
}

/** A column square at the baseline and rounded at the data end, as a bar should be. */
function roundedTop(x: number, y: number, w: number, h: number, r: number): string {
  const rr = Math.min(r, h, w / 2);
  return [
    `M${x},${y + h}`, `V${y + rr}`, `Q${x},${y} ${x + rr},${y}`, `H${x + w - rr}`,
    `Q${x + w},${y} ${x + w},${y + rr}`, `V${y + h}`, "Z",
  ].join(" ");
}
