"use client";

import Link from "next/link";
import { useState } from "react";
import { Confirm } from "@/components/Confirm";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { useLoad } from "@/lib/search";
import { dayStart } from "@/lib/time";
import { fetchMarkets } from "../markets/api";
import {
  fetchOverrides, removeFee, removeMarketLimit, removeOrderLimit, setFee, setMarketLimit, setOrderLimit,
  type FeeGrant, type LimitGrant, type LimitView, type Overrides,
} from "./overridesApi";

const load = (key: string) => fetchOverrides(Number(key.split(":")[0]));
const loadMarkets = () => fetchMarkets();

/**
 * One customer's fee and order limits, and where each comes from. A sub-account pays exactly what
 * its master pays, so a fee override is set on the master only; order limits are per account. Every
 * change applies from the next order.
 */
export function FeesAndLimits({ userId, masterId, canAct }: { userId: number; masterId: number | null; canAct: boolean }) {
  const [reload, setReload] = useState(0);
  const { data, error } = useLoad(load, `${userId}:${reload}`);
  const [latest, setLatest] = useState<Overrides | null>(null);
  const [editing, setEditing] = useState<"fee" | "removeFee" | { limit: LimitView | "new" } | { remove: LimitView } | null>(null);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const o = latest ?? data;
  if (!o) return <p className="text-muted" aria-busy>Loading…</p>;
  const done = (next: Overrides) => { setLatest(next); setEditing(null); setReload((n) => n + 1); };
  const limits = [o.orderLimit, ...o.marketLimits];

  return (
    <>
      <section className="card px-4 py-4 sm:px-5 flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="m-0 text-base font-semibold">Fee</h2>
          {canAct && !o.fee.subAccount && (
            <div className="flex gap-2">
              {o.fee.override && <button className="btn btn-sm btn-danger" onClick={() => setEditing("removeFee")}>Remove override</button>}
              <button className="btn btn-sm btn-primary" onClick={() => setEditing("fee")}>{o.fee.override ? "Change override" : "Set override"}</button>
            </div>
          )}
        </div>
        <p className="m-0 text-lg">
          Maker <span className="font-mono font-semibold">{o.fee.makerFeeBps}</span> bps · Taker <span className="font-mono font-semibold">{o.fee.takerFeeBps}</span> bps
        </p>
        <p className="m-0 text-muted">
          From {o.fee.subAccount ? "their master's " : o.fee.source === "OVERRIDE" ? "their " : ""}
          {o.fee.source === "OVERRIDE" ? "override" : (o.fee.tierName ?? `level ${o.fee.tierLevel}`)}
          {o.fee.subAccount && masterId && <> · <Link href={`/users/${masterId}`}>open master</Link></>}
        </p>
        {o.fee.override && <GrantLine grant={o.fee.override} what={o.fee.subAccount ? "Master's override" : "Their override"} />}
      </section>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="m-0 text-base font-semibold">Order limits</h2>
        {canAct && <button className="btn btn-sm" onClick={() => setEditing({ limit: "new" })}>Add market limit</button>}
      </div>
      <Table
        empty="No limits."
        head={["Applies to", "Open orders", "Open stop orders", "Reason", "Expires", "Granted by", ""]}
        right={[1, 2]}
        rows={limits.map((l) => ({
          key: l.symbol ?? "account",
          cells: [
            <span key="s" className="font-semibold">{l.symbol ?? "All markets"}</span>,
            <Cap key="o" value={l.own?.maxOrders ?? null} fallback={l.defaultOrders} grant={l.own} />,
            <Cap key="t" value={l.own?.maxStops ?? null} fallback={l.defaultStops} grant={l.own} />,
            <span key="r" className="text-muted">{l.own?.reason ?? "—"}</span>,
            <Expiry key="e" grant={l.own} />,
            <span key="g" className="text-muted">{l.own?.grantedBy ?? "—"}</span>,
            canAct ? (
              <span key="a" className="flex justify-end gap-2">
                <button className="btn btn-sm" onClick={() => setEditing({ limit: l })}>{l.own ? "Change" : "Set"}</button>
                {l.own && <button className="btn btn-sm btn-danger" onClick={() => setEditing({ remove: l })}>Remove</button>}
              </span>
            ) : <span key="a" />,
          ],
        }))}
      />

      {editing === "fee" && <FeeDialog userId={userId} grant={o.fee.override} onClose={() => setEditing(null)} onDone={done} />}
      {editing === "removeFee" && (
        <Confirm title="Remove fee override" confirmLabel="Remove" tone="danger" code
                 body="From their next order they pay their tier's rate again, and so do their sub-accounts."
                 onClose={() => setEditing(null)}
                 onConfirm={async (_, code) => done(await removeFee(userId, code))} />
      )}
      {editing && typeof editing === "object" && "limit" in editing && (
        <LimitDialog userId={userId} limit={editing.limit} taken={o.marketLimits.map((l) => l.symbol!)}
                     onClose={() => setEditing(null)} onDone={done} />
      )}
      {editing && typeof editing === "object" && "remove" in editing && (
        <Confirm title="Remove limit" confirmLabel="Remove" tone="danger"
                 body={`${editing.remove.symbol ?? "All markets"} goes back to the default from their next order.`}
                 onClose={() => setEditing(null)}
                 onConfirm={async () => done(editing.remove.symbol
                   ? await removeMarketLimit(userId, editing.remove.symbol) : await removeOrderLimit(userId))} />
      )}
    </>
  );
}

/** A cap: the override's value when it sets one, else the default it leaves alone. */
function Cap({ value, fallback, grant }: { value: number | null; fallback: number; grant: LimitGrant | null }) {
  if (value === null || !grant?.effective) return <span className="font-mono text-muted">{fallback.toLocaleString()} <span className="text-dim">default</span></span>;
  return <span className="font-mono font-semibold">{value.toLocaleString()}</span>;
}

function Expiry({ grant }: { grant: { expiresAt: string | null; effective: boolean } | null }) {
  if (!grant) return <span className="text-dim">—</span>;
  if (!grant.effective) return <span className="pill pill-off">Expired</span>;
  return <span className="font-mono text-muted">{grant.expiresAt ? grant.expiresAt.slice(0, 10) : "never"}</span>;
}

function GrantLine({ grant, what }: { grant: FeeGrant; what: string }) {
  return (
    <p className="m-0 text-[13px] text-muted">
      {what}: maker {grant.makerFeeBps} / taker {grant.takerFeeBps} bps · {grant.reason ?? "no reason"} · by {grant.grantedBy ?? "—"} · expires{" "}
      {grant.effective ? (grant.expiresAt?.slice(0, 10) ?? "never") : "— expired"}
    </p>
  );
}

/** The last day it applies, inclusive, as the instant it ends; "" for no end. */
const expiryOf = (day: string) => (day ? dayStart(day, 1) : null);
const dayOf = (instant: string | null) => (instant ? new Date(new Date(instant).getTime() - 1).toLocaleDateString("sv-SE") : "");

function FeeDialog({ userId, grant, onClose, onDone }: {
  userId: number; grant: FeeGrant | null; onClose: () => void; onDone: (o: Overrides) => void;
}) {
  const [form, setForm] = useState({
    maker: grant ? String(grant.makerFeeBps) : "", taker: grant ? String(grant.takerFeeBps) : "",
    reason: grant?.reason ?? "", expires: grant?.effective ? dayOf(grant.expiresAt) : "",
  });
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const whole = (v: string) => /^-?\d+$/.test(v.trim());
  const ready = whole(form.maker) && whole(form.taker) && form.reason.trim() && code.length === 6;
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  return (
    <Dialog title={grant ? "Change fee override" : "Set fee override"} onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => onDone(await setFee(userId, {
              makerFeeBps: Number(form.maker), takerFeeBps: Number(form.taker), reason: form.reason.trim(),
              expiresAt: expiryOf(form.expires),
            }, code)))}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>Save</button>
            </>}>
      <p className="m-0 text-muted leading-relaxed">Applies to their next order, and their sub-accounts&apos;. Orders on the book keep their rate.</p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Maker fee (bps, negative is a rebate)"><input aria-label="Maker fee" value={form.maker} onChange={set("maker")} className="input font-mono" autoFocus /></Field>
        <Field label="Taker fee (bps)"><input aria-label="Taker fee" value={form.taker} onChange={set("taker")} className="input font-mono" /></Field>
      </div>
      <Field label="Reason"><input aria-label="Reason" value={form.reason} onChange={set("reason")} maxLength={255} className="input" /></Field>
      <Field label="Last day — blank for no end"><input type="date" aria-label="Last day" value={form.expires} onChange={set("expires")} className="input" /></Field>
      <CodeInput value={code} onChange={setCode} />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}

/** A cap left blank is not overridden: that one stays at the default. */
function LimitDialog({ userId, limit, taken, onClose, onDone }: {
  userId: number; limit: LimitView | "new"; taken: string[]; onClose: () => void; onDone: (o: Overrides) => void;
}) {
  const adding = limit === "new";
  const existing = adding ? null : limit;
  const markets = useLoad(loadMarkets, adding ? "markets" : "");
  const [form, setForm] = useState({
    symbol: existing?.symbol ?? "", orders: existing?.own?.maxOrders?.toString() ?? "",
    stops: existing?.own?.maxStops?.toString() ?? "", reason: existing?.own?.reason ?? "",
    expires: existing?.own?.effective ? dayOf(existing.own.expiresAt) : "",
  });
  const { busy, error, run } = useBusy();
  const count = (v: string) => (v.trim() === "" ? null : Number(v));
  const valid = (v: string) => v.trim() === "" || /^\d+$/.test(v.trim());
  const ready = valid(form.orders) && valid(form.stops) && (form.orders.trim() || form.stops.trim()) && form.reason.trim()
    && (!adding || form.symbol);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  const scope = adding ? form.symbol : existing!.symbol;
  const title = adding ? "Add market limit" : `Limit for ${existing!.symbol ?? "all markets"}`;

  return (
    <Dialog title={title} onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => {
              const body = { maxOrders: count(form.orders), maxStops: count(form.stops), reason: form.reason.trim(),
                             expiresAt: expiryOf(form.expires) };
              onDone(scope ? await setMarketLimit(userId, { ...body, symbol: scope }) : await setOrderLimit(userId, body));
            })}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>Save</button>
            </>}>
      {adding && (
        <Field label="Market">
          <select aria-label="Market" value={form.symbol} onChange={set("symbol")} className="input">
            <option value="">Choose</option>
            {(markets.data?.markets ?? []).filter((m) => !taken.includes(m.symbol)).map((m) => (
              <option key={m.id} value={m.symbol}>{m.symbol}</option>
            ))}
          </select>
        </Field>
      )}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Open orders — blank keeps the default"><input aria-label="Open orders" value={form.orders} onChange={set("orders")} className="input font-mono" /></Field>
        <Field label="Open stop orders — blank keeps the default"><input aria-label="Open stop orders" value={form.stops} onChange={set("stops")} className="input font-mono" /></Field>
      </div>
      <Field label="Reason"><input aria-label="Reason" value={form.reason} onChange={set("reason")} maxLength={255} className="input" /></Field>
      <Field label="Last day — blank for no end"><input type="date" aria-label="Last day" value={form.expires} onChange={set("expires")} className="input" /></Field>
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
