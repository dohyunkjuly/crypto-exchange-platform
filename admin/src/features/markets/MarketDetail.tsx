"use client";

import { useState } from "react";
import { Confirm } from "@/components/Confirm";
import { Fact } from "@/components/Fact";
import { exact } from "@/lib/money";
import { useLoad } from "@/lib/search";
import { useAdmin } from "@/lib/session";
import { fetchMarket, setLimits, setStatus, type Limits, type Market } from "./api";
import { MarketStatus } from "./MarketStatus";

type Move = { to: "TRADING" | "CANCEL_ONLY"; title: string; label: string; body: string; tone: "primary" | "danger" };

/** The one move each status offers staff: open a new market, wind a live one down, or resume it. */
function moveFor(m: Market): Move | null {
  switch (m.status) {
    case "PRE_TRADING":
      return { to: "TRADING", title: "Open this market", label: "Open market", tone: "primary",
               body: `${m.symbol} starts taking orders now, and appears on the web and the API within a minute.` };
    case "TRADING":
      return { to: "CANCEL_ONLY", title: "Set cancel-only", label: "Set cancel-only", tone: "danger",
               body: `${m.symbol} stops taking new orders and amends. Customers can still cancel what they have on the book.` };
    case "CANCEL_ONLY":
    case "HALTED":
      return { to: "TRADING", title: "Resume trading", label: "Resume trading", tone: "primary",
               body: `${m.symbol} takes new orders again.` };
    default:
      return null;
  }
}

/** One market: what was fixed at listing, its status, and the limits staff may change. */
export function MarketDetail({ id }: { id: string }) {
  const admin = useAdmin();
  const [reload, setReload] = useState(0);
  const { data, error } = useLoad(fetchMarket, `${id}`);
  const [latest, setLatest] = useState<Market | null>(null);
  const [moving, setMoving] = useState<Move | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const m = latest ?? data;
  if (!m || !admin) return <p className="text-muted" aria-busy>Loading…</p>;
  const canAct = admin.role !== "VIEWER";
  const move = canAct ? moveFor(m) : null;
  const done = (next: Market, message: string) => { setLatest(next); setMoving(null); setNotice(message); setReload(reload + 1); };

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}
      <section className="card px-4 py-4 sm:px-5 sm:py-[18px] flex flex-col sm:flex-row items-start gap-4 sm:gap-5">
        <div className="flex-1 min-w-0 flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="m-0 text-lg font-semibold">{m.symbol}</h1>
            <MarketStatus status={m.status} />
          </div>
          <dl className="m-0 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4">
            <Fact label="Base">{m.base}</Fact>
            <Fact label="Quote">{m.quote}</Fact>
            <Fact label="Engine partition"><span className="font-mono">{m.commandPartition}</span></Fact>
            <Fact label="Price tick"><span className="font-mono">{exact(m.priceTick)}</span></Fact>
            <Fact label="Quantity step"><span className="font-mono">{exact(m.qtyStep)}</span></Fact>
            <Fact label="Listed"><span className="font-mono">{m.createdAt.slice(0, 10)}</span></Fact>
          </dl>
        </div>
        {move && (
          <div className="shrink-0 w-full sm:w-44 flex flex-col">
            <button className={`btn ${move.tone === "danger" ? "btn-danger" : "btn-primary"}`} onClick={() => setMoving(move)}>{move.label}</button>
          </div>
        )}
      </section>

      <LimitsForm key={`${m.id}-${reload}`} market={m} canAct={canAct} onSaved={(next) => done(next, "Limits saved.")} />

      {moving && (
        <Confirm title={moving.title} body={moving.body} confirmLabel={moving.label} tone={moving.tone} code
                 onClose={() => setMoving(null)}
                 onConfirm={async (_, code) => { done(await setStatus(m.id, moving.to, code), `${m.symbol} is now ${moving.to === "TRADING" ? "trading" : "cancel-only"}.`); }} />
      )}
    </>
  );
}

type Draft = Record<Exclude<keyof Limits, "marketOrdersEnabled" | "stopOrdersEnabled">, string>
  & { marketOrdersEnabled: boolean; stopOrdersEnabled: boolean };

const TEXT: { key: keyof Draft; label: string; quote?: boolean; optional?: boolean }[] = [
  { key: "minQty", label: "Min quantity" },
  { key: "maxQty", label: "Max quantity" },
  { key: "minNotional", label: "Min order value", quote: true },
  { key: "maxNotional", label: "Max order value", quote: true, optional: true },
  { key: "marketMinQty", label: "Min market-order quantity", optional: true },
  { key: "marketMaxQty", label: "Max market-order quantity", optional: true },
  { key: "minQuoteQty", label: "Min market-buy spend", quote: true, optional: true },
  { key: "maxQuoteQty", label: "Max market-buy spend", quote: true, optional: true },
  { key: "priceBandBps", label: "Price band (bps from last price)", optional: true },
  { key: "maxOpenOrders", label: "Open orders per customer" },
  { key: "maxOpenConditionalOrders", label: "Open stop orders per customer" },
];

function draftOf(l: Limits): Draft {
  const s = (v: string | number | null) => (v === null ? "" : String(v));
  return {
    minQty: l.minQty, maxQty: l.maxQty, minNotional: l.minNotional, maxNotional: s(l.maxNotional),
    marketMinQty: s(l.marketMinQty), marketMaxQty: s(l.marketMaxQty), minQuoteQty: s(l.minQuoteQty), maxQuoteQty: s(l.maxQuoteQty),
    priceBandBps: s(l.priceBandBps), maxOpenOrders: s(l.maxOpenOrders), maxOpenConditionalOrders: s(l.maxOpenConditionalOrders),
    marketOrdersEnabled: l.marketOrdersEnabled, stopOrdersEnabled: l.stopOrdersEnabled,
  };
}

function limitsOf(d: Draft): Limits {
  const opt = (v: string) => (v.trim() === "" ? null : v.trim());
  return {
    minQty: d.minQty.trim(), maxQty: d.maxQty.trim(), minNotional: d.minNotional.trim(), maxNotional: opt(d.maxNotional),
    marketMinQty: opt(d.marketMinQty), marketMaxQty: opt(d.marketMaxQty), minQuoteQty: opt(d.minQuoteQty), maxQuoteQty: opt(d.maxQuoteQty),
    priceBandBps: opt(d.priceBandBps) === null ? null : Number(d.priceBandBps), maxOpenOrders: Number(d.maxOpenOrders),
    maxOpenConditionalOrders: Number(d.maxOpenConditionalOrders),
    marketOrdersEnabled: d.marketOrdersEnabled, stopOrdersEnabled: d.stopOrdersEnabled,
  };
}

/** The limits around the fixed tick and step. Saved together, with a code, since they change what can trade. */
function LimitsForm({ market, canAct, onSaved }: { market: Market; canAct: boolean; onSaved: (m: Market) => void }) {
  const [draft, setDraft] = useState<Draft>(() => draftOf(market.limits));
  const [saving, setSaving] = useState(false);
  const changed = JSON.stringify(limitsOf(draft)) !== JSON.stringify(market.limits);

  return (
    <section className="card px-4 py-4 sm:px-5 sm:py-[18px] flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="m-0 text-base font-semibold">Limits</h2>
        {canAct && <button className="btn btn-primary" disabled={!changed} onClick={() => setSaving(true)}>Save limits</button>}
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {TEXT.map((f) => (
          <label key={f.key} className="flex flex-col gap-1.5">
            <span className="text-[13px] font-medium">{f.label}{f.quote ? ` (${market.quote})` : ""}</span>
            <input aria-label={f.label} className="input font-mono" disabled={!canAct} value={draft[f.key] as string}
                   placeholder={f.optional ? "No limit" : undefined}
                   onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })} />
          </label>
        ))}
      </div>
      <div className="flex flex-wrap gap-5">
        {([["marketOrdersEnabled", "Market orders"], ["stopOrdersEnabled", "Stop orders"]] as const).map(([k, label]) => (
          <label key={k} className="flex items-center gap-2">
            <input type="checkbox" disabled={!canAct} checked={draft[k]} onChange={(e) => setDraft({ ...draft, [k]: e.target.checked })} />
            {label}
          </label>
        ))}
      </div>
      {saving && (
        <Confirm title="Save limits" confirmLabel="Save" code
                 body={`The new limits apply to ${market.symbol}'s next order. Orders already on the book keep what they were placed with.`}
                 onClose={() => setSaving(false)}
                 onConfirm={async (_, code) => { const next = await setLimits(market.id, limitsOf(draft), code); setSaving(false); onSaved(next); }} />
      )}
    </section>
  );
}
