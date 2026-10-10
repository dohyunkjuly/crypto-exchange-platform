"use client";

import Link from "next/link";
import { useState } from "react";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { exact } from "@/lib/money";
import { useLoad } from "@/lib/search";
import { useAdmin } from "@/lib/session";
import { fetchMarkets, listMarket, type Listing, type Status } from "./api";
import { MarketStatus } from "./MarketStatus";

const load = () => fetchMarkets();

/** Every market, whatever its status: a handful of rows, so filtered here rather than on the server. */
export function Markets() {
  const admin = useAdmin();
  const [reload, setReload] = useState(0);
  const { data, error } = useLoad(load, String(reload));
  const [status, setStatus] = useState<Status | "">("");
  const [listing, setListing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const rows = (data?.markets ?? []).filter((m) => !status || m.status === status);

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value as Status | "")} className="input w-full sm:w-auto">
          <option value="">Any status</option>
          <option value="TRADING">Trading</option>
          <option value="CANCEL_ONLY">Cancel-only</option>
          <option value="PRE_TRADING">Not open</option>
        </select>
        {admin && admin.role !== "VIEWER" && data && (
          <button className="btn btn-primary" onClick={() => setListing(true)}>List a market</button>
        )}
      </div>

      <Table
        empty={!data ? "Loading…" : status ? "No market has that status." : "No markets yet."}
        head={["Market", "Status", "Partition", "Price tick", "Quantity step", "Min quantity", "Max quantity", "Min order value"]}
        right={[2, 3, 4, 5, 6, 7]}
        rows={rows.map((m) => ({
          key: String(m.id),
          cells: [
            <Link key="s" href={`/markets/${m.id}`} className="font-semibold text-text hover:text-accent">{m.symbol}</Link>,
            <MarketStatus key="st" status={m.status} />,
            <span key="p" className="font-mono">{m.commandPartition}</span>,
            <span key="t" className="font-mono">{exact(m.priceTick)}</span>,
            <span key="q" className="font-mono">{exact(m.qtyStep)}</span>,
            <span key="mn" className="font-mono">{exact(m.limits.minQty)}</span>,
            <span key="mx" className="font-mono">{exact(m.limits.maxQty)}</span>,
            <span key="v" className="font-mono">{exact(m.limits.minNotional)} <span className="text-muted">{m.quote}</span></span>,
          ],
        }))}
      />

      {listing && data && (
        <ListDialog listing={data} onClose={() => setListing(false)}
                    onDone={(symbol) => { setListing(false); setNotice(`${symbol} listed. It opens when you open it.`); setReload((n) => n + 1); }} />
      )}
    </>
  );
}

/**
 * The pair, partition, tick and step can never change, so they are asked for here and nowhere
 * else. The limits start at the usual values and are edited on the market's page. The market is
 * listed closed: nothing trades until it is opened.
 */
function ListDialog({ listing, onClose, onDone }: { listing: Listing; onClose: () => void; onDone: (symbol: string) => void }) {
  const [form, setForm] = useState({ base: "", quote: "", partition: "", priceTick: "", qtyStep: "", minQty: "", maxQty: "", minNotional: "" });
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value.trim() });
  const ready = form.base && form.quote && form.base !== form.quote && form.partition !== ""
    && form.priceTick && form.qtyStep && form.minQty && form.maxQty && form.minNotional && code.length === 6;
  const base = listing.assets.find((a) => String(a.id) === form.base);
  const quote = listing.assets.find((a) => String(a.id) === form.quote);
  // Which markets already share each partition: a busy market wants one of its own.
  const using = (p: number) => listing.markets.filter((m) => m.commandPartition === p).map((m) => m.symbol);

  return (
    <Dialog title="List a market" onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => {
              const made = await listMarket({
                baseAssetId: Number(form.base), quoteAssetId: Number(form.quote), commandPartition: Number(form.partition),
                priceTick: form.priceTick, qtyStep: form.qtyStep,
                limits: { minQty: form.minQty, maxQty: form.maxQty, minNotional: form.minNotional, maxNotional: null,
                          marketMinQty: null, marketMaxQty: null, minQuoteQty: null, maxQuoteQty: null, priceBandBps: null,
                          maxOpenOrders: 200, maxOpenConditionalOrders: 50, marketOrdersEnabled: true, stopOrdersEnabled: false },
              }, code);
              onDone(made.symbol);
            })}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>List</button>
            </>}>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Base asset">
          <select aria-label="Base asset" value={form.base} onChange={set("base")} className="input">
            <option value="">Choose</option>
            {listing.assets.map((a) => <option key={a.id} value={a.id}>{a.symbol}</option>)}
          </select>
        </Field>
        <Field label="Quote asset">
          <select aria-label="Quote asset" value={form.quote} onChange={set("quote")} className="input">
            <option value="">Choose</option>
            {listing.assets.map((a) => <option key={a.id} value={a.id}>{a.symbol}</option>)}
          </select>
        </Field>
      </div>
      {base && quote && <p className="m-0 text-muted">Listed as <span className="font-semibold text-text">{base.symbol}{quote.symbol}</span>.</p>}
      <Field label="Engine partition — fixed once listed">
        <select aria-label="Engine partition" value={form.partition} onChange={set("partition")} className="input">
          <option value="">Choose</option>
          {Array.from({ length: listing.partitionCount }, (_, p) => (
            <option key={p} value={p}>{p}{using(p).length ? ` · ${using(p).join(", ")}` : " · free"}</option>
          ))}
        </select>
      </Field>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Price tick — fixed once listed"><input aria-label="Price tick" value={form.priceTick} onChange={set("priceTick")} className="input font-mono" placeholder="0.01" /></Field>
        <Field label="Quantity step — fixed once listed"><input aria-label="Quantity step" value={form.qtyStep} onChange={set("qtyStep")} className="input font-mono" placeholder="0.001" /></Field>
        <Field label="Min quantity"><input aria-label="Min quantity" value={form.minQty} onChange={set("minQty")} className="input font-mono" /></Field>
        <Field label="Max quantity"><input aria-label="Max quantity" value={form.maxQty} onChange={set("maxQty")} className="input font-mono" /></Field>
        <Field label={`Min order value${quote ? ` (${quote.symbol})` : ""}`}><input aria-label="Min order value" value={form.minNotional} onChange={set("minNotional")} className="input font-mono" /></Field>
      </div>
      <CodeInput value={code} onChange={setCode} />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
