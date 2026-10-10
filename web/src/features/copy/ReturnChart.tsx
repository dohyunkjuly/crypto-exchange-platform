"use client";

import { useLocale } from "@/lib/i18n";

const W = 600; const H = 220; const PAD = { top: 12, right: 12, bottom: 28, left: 44 };

/**
 * The cumulative return over the snapshots, as an area under a line, with a percentage scale on
 * the left and dates along the bottom. Drawn to the box: the SVG scales, the text does not.
 */
export function ReturnChart({ points, label }: { points: { day: string; value: string }[]; label: string }) {
  const locale = useLocale();
  if (points.length < 2) return null;
  const values = points.map((p) => Number(p.value));
  const lo = Math.min(0, ...values); const hi = Math.max(0, ...values);
  const span = hi - lo || 1;
  const x = (i: number) => PAD.left + (i / (points.length - 1)) * (W - PAD.left - PAD.right);
  const y = (v: number) => PAD.top + (1 - (v - lo) / span) * (H - PAD.top - PAD.bottom);
  const line = values.map((v, i) => `${x(i)},${y(v)}`).join(" ");
  const area = `${x(0)},${y(lo)} ${line} ${x(values.length - 1)},${y(lo)}`;
  const up = values[values.length - 1] >= 0;
  // Four horizontal rules at round percentages, so the scale reads at a glance.
  const step = niceStep(span / 4);
  const ticks: number[] = [];
  for (let v = Math.ceil(lo / step) * step; v <= hi + 1e-9; v += step) ticks.push(Number(v.toFixed(6)));
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { month: "2-digit", day: "2-digit" }).format(new Date(`${iso}T00:00:00Z`));
  const labelled = [0, Math.floor((points.length - 1) / 2), points.length - 1].filter((v, i, a) => a.indexOf(v) === i);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className={`h-56 w-full ${up ? "text-up" : "text-down"}`} role="img" aria-label={label}>
      {ticks.map((v) => (
        <g key={v}>
          <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} className="stroke-border" strokeDasharray="2 4" />
          <text x={PAD.left - 8} y={y(v)} textAnchor="end" dominantBaseline="middle" className="fill-[var(--color-muted)] text-[11px]">{v}%</text>
        </g>
      ))}
      <polygon points={area} fill="currentColor" opacity="0.12" />
      <polyline points={line} fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      {labelled.map((i) => (
        <text key={i} x={x(i)} y={H - 8} textAnchor={i === 0 ? "start" : i === points.length - 1 ? "end" : "middle"}
              className="fill-[var(--color-muted)] text-[11px]">{date(points[i].day)}</text>
      ))}
    </svg>
  );
}

/** 1, 2, 5 or 10 times a power of ten, whichever is nearest above the raw step. */
function niceStep(raw: number): number {
  const pow = 10 ** Math.floor(Math.log10(raw || 1));
  const n = raw / pow;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow;
}
