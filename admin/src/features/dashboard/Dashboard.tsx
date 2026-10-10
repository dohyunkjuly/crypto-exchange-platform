"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Stat } from "@/components/Stat";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { amount, isZero } from "@/lib/money";
import { fetchAudit, type Entry } from "@/features/audit/api";
import { RevenueChart } from "@/features/treasury/RevenueChart";
import { fetchDashboard, type Compare, type Dashboard as Data } from "./api";

const RANGES = [7, 30, 90] as const;
// The busiest markets, and always this many rows: the table's height is fixed, so the chart
// beside it is too. The whole list is the Markets page's.
const TOP_PAIRS = 5;
const NONE = { adminId: "", action: "", targetType: "", targetId: "", from: "", to: "" };

/**
 * The console's home: today so far, against yesterday up to the same time of day -- a whole
 * yesterday would make every morning look like a collapse. Then the shape of the month's fees,
 * which markets traded, and what staff have been doing.
 */
export function Dashboard() {
  const [data, setData] = useState<Data | null>(null);
  const [activity, setActivity] = useState<Entry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [range, setRange] = useState<(typeof RANGES)[number]>(30);

  useEffect(() => {
    let cancelled = false;
    fetchDashboard()
      .then((d) => { if (!cancelled) setData(d); })
      .catch((e: unknown) => { if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setError(e.message); });
    fetchAudit(NONE, 0, 8)
      .then((p) => { if (!cancelled) setActivity(p.items); })
      .catch(() => { if (!cancelled) setActivity([]); });
    return () => { cancelled = true; };
  }, []);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!data) return <p className="text-muted" aria-busy>Loading…</p>;

  return (
    <>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <Stat label="Signups today" value={Number(data.signups.today).toLocaleString()} note={delta(data.signups)} />
        <Stat label="Trades today" value={Number(data.trades.today).toLocaleString()} note={delta(data.trades)} />
        <Stat label="Volume today · USDT" value={amount(data.volume.today, 2)} note={delta(data.volume)} />
        <Stat label="Fees today · USDT" value={amount(data.fees.today, 2)} note={delta(data.fees)} />
      </div>

      {/* Stretched, not items-start: the chart fills its card to the table's fixed height. */}
      <div className="grid grid-cols-1 lg:grid-cols-[2fr_1fr] gap-3">
        <section className="card px-[18px] py-4 flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="font-semibold">Fees per day</span>
            <div role="tablist" aria-label="Range" className="segmented">
              {RANGES.map((r) => (
                <button key={r} role="tab" aria-selected={range === r} onClick={() => setRange(r)}>{r}d</button>
              ))}
            </div>
          </div>
          <RevenueChart key={range} asset="USDT" decimals={2} days={range} fill />
        </section>

        <Table
          empty="Nothing has traded today."
          head={["Pair", "Volume today", "Trades", "vs yesterday"]}
          right={[1, 2, 3]}
          rows={topPairs(data.pairs).map((p, i) => p === null
            // Blank rows keep the height; the first one says why when nothing traded at all.
            ? { key: `empty-${i}`, cells: [
              <span key="s" className="text-muted">{i === 0 ? "Nothing has traded today." : "\u00a0"}</span>, "", "", ""] }
            : {
              key: p.symbol,
              cells: [
                <span key="s" className="font-semibold">{p.symbol}</span>,
                <span key="v" className={`font-mono ${isZero(p.volumeToday) ? "text-dim" : ""}`}>{amount(p.volumeToday, 2)}</span>,
                <span key="t" className="font-mono text-muted">{p.tradesToday.toLocaleString()}</span>,
                <span key="d">{delta({ today: p.volumeToday, yesterday: p.volumeYesterday }, true) ?? <span className="text-muted">—</span>}</span>,
              ],
            })}
        />
      </div>

      <section className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between">
          <h2 className="m-0 text-base font-semibold">Recent activity</h2>
          <Link href="/audit" className="text-[13px]">Audit log</Link>
        </div>
        <Table
          empty="No admin has done anything yet."
          head={["When (UTC)", "Who", "What", "Target"]}
          rows={(activity ?? []).map((e) => ({
            key: String(e.id),
            cells: [
              <span key="w" className="font-mono text-muted">{e.createdAt.slice(5, 16).replace("T", " ")}</span>,
              e.adminEmail ? <span key="a">{e.adminEmail}</span> : <span key="a" className="text-dim">—</span>,
              <span key="ac" className={`pill ${e.action.endsWith("FAILED") ? "pill-bad" : "pill-off"} font-mono`}>{e.action}</span>,
              e.targetType === "user" && e.targetId
                ? <Link key="t" href={`/users/${e.targetId}`} className="font-mono">user {e.targetId}</Link>
                : <span key="t" className="font-mono text-muted">{[e.targetType, e.targetId].filter(Boolean).join(" ") || "—"}</span>,
            ],
          }))}
        />
      </section>
    </>
  );
}

/** The top pairs by today's volume, padded with blank rows to TOP_PAIRS so the table never changes height. */
function topPairs(pairs: Data["pairs"]): (Data["pairs"][number] | null)[] {
  const top = [...pairs].sort((a, b) => Number(b.volumeToday) - Number(a.volumeToday)).slice(0, TOP_PAIRS);
  return [...top, ...Array<null>(TOP_PAIRS - top.length).fill(null)];
}

/**
 * Today so far against yesterday to the same time. A percentage is fine as a float -- it is read,
 * never summed or sent; the amounts beside it stay strings. Nothing yesterday means no ratio, and
 * no line either: a row of "none" says nothing. A pair new today is the one worth marking.
 */
function delta(c: Compare, short = false): React.ReactNode {
  const today = Number(c.today);
  const yesterday = Number(c.yesterday);
  if (yesterday === 0) return short && today !== 0 ? <span className="text-muted">new</span> : null;
  const change = ((today - yesterday) / yesterday) * 100;
  const tone = change > 0 ? "text-success" : change < 0 ? "text-danger" : "text-muted";
  const text = `${change > 0 ? "↑" : change < 0 ? "↓" : ""} ${Math.abs(change).toFixed(1)}%`;
  return <span className={`font-mono ${tone}`}>{short ? text : `${text} vs same time yesterday`}</span>;
}
