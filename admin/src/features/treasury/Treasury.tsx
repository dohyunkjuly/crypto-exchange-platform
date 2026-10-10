"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Pager, usePage } from "@/components/Pager";
import { Stat } from "@/components/Stat";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { amount, isZero } from "@/lib/money";
import { useNow } from "@/lib/now";
import { useAdmin } from "@/lib/session";
import { RevenueChart } from "./RevenueChart";
import { fetchMoves, fetchTreasury, moveFees, payOut, type AssetLine, type Move, type Period, type Treasury as Data } from "./api";

type View = "held" | "earned" | "moves";
/** One row per asset, so this is the number of assets a screen holds before it pages. */
const PER_PAGE = 20;

const PERIODS: { id: Period; label: string }[] = [
  { id: "today", label: "Today" },
  { id: "week", label: "7 days" },
  { id: "month", label: "Month" },
  { id: "all", label: "All time" },
];

/**
 * What the exchange holds and what trading has earned, in coins (docs/reference/treasury.md).
 *
 * <p>Two views rather than two tables down one page: each is a row per asset, and forty assets
 * twice is a page nobody scrolls. They are different kinds of number too -- a reading taken now
 * against a total over a window -- so the period control lives on Earned alone. The view is in
 * the URL, so a refresh keeps it and a link means the one you sent.
 *
 * <p>An OPERATOR can move the exchange's money, in two ways only: fees on to the treasury, and the
 * treasury out to a customer. Never money from nothing (docs/reference/treasury.md).
 */
export function Treasury() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const view: View = params.get("view") === "earned" ? "earned" : params.get("view") === "moves" ? "moves" : "held";
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const admin = useAdmin();
  const canMove = admin?.role === "OPERATOR" || admin?.role === "SUPERADMIN";
  const [moving, setMoving] = useState<AssetLine | null>(null);
  const [paying, setPaying] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchTreasury()
      .then((d) => { if (!cancelled) setData(d); })
      .catch((e: unknown) => {
        // A 401 is the session hook's business; it is already sending them to the login.
        if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setError(e.message);
      });
    return () => { cancelled = true; };
  }, []);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!data) return <p className="text-muted" aria-busy>Loading…</p>;

  const usdt = data.assets.find((a) => a.symbol === "USDT");

  return (
    <>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <SweepStat sweptAt={data.sweptAt} />
        <Stat label="Assets held" value={data.assets.length} note="by customers or the exchange" />
        {usdt && <Stat label="USDT fees today" value={amount(usdt.periods.today.fees, usdt.displayDecimals)}
                       note={`${amount(usdt.periods.today.rebates, usdt.displayDecimals)} paid back in rebates`} />}
      </div>

      {/* The action shares the tab bar's line: it belongs to the page, not to one view. */}
      <div className="flex items-center gap-3 shadow-[inset_0_-1px_0_var(--border)]">
        <div role="tablist" aria-label="View" className="tabs flex-1 min-w-0">
          {([["held", "Held now"], ["earned", "Earned"], ["moves", "Moves"]] as [View, string][]).map(([id, label]) => (
            <button key={id} role="tab" aria-selected={view === id} className="tab"
                    onClick={() => router.replace(id === "held" ? pathname : `${pathname}?view=${id}`)}>
              {label}
            </button>
          ))}
        </div>
        {canMove && <button className="btn btn-sm shrink-0" onClick={() => setPaying(true)}>Pay a customer</button>}
      </div>

      {view === "held" ? <Held assets={data.assets} onMove={canMove ? setMoving : undefined} />
        : view === "earned" ? <Earned assets={data.assets} /> : <Moves />}

      {moving && <MoveFees asset={moving} onClose={() => setMoving(null)} onDone={(d) => { setData(d); setMoving(null); }} />}
      {paying && <PayOut assets={data.assets} onClose={() => setPaying(false)} onDone={(d) => { setData(d); setPaying(false); }} />}
    </>
  );
}

/** Fees on to the treasury. The amount opens at everything the fee account holds of it. */
function MoveFees({ asset, onClose, onDone }: { asset: AssetLine; onClose: () => void; onDone: (data: Data) => void }) {
  const [id] = useState(() => crypto.randomUUID());
  const [amountText, setAmount] = useState(asset.feeAccount);
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const ready = amountText.trim() !== "" && reason.trim() !== "" && code.length === 6;

  return (
    <Dialog title={`Move ${asset.symbol} fees to the treasury`} onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => onDone(await moveFees({ id, asset: asset.symbol, amount: amountText.trim(), reason: reason.trim() }, code)))}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>Move</button>
            </>}>
      <p className="m-0 text-muted">The fee account holds {amount(asset.feeAccount, asset.displayDecimals)} {asset.symbol}.</p>
      <Field label={`Amount (${asset.symbol})`}>
        <input className="input font-mono" inputMode="decimal" value={amountText} onChange={(e) => setAmount(e.target.value)} />
      </Field>
      <Field label="Reason">
        <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} autoFocus />
      </Field>
      <CodeInput value={code} onChange={setCode} label="Authenticator code" />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}

/** Treasury to a customer's main account, named by email or id. */
function PayOut({ assets, onClose, onDone }: { assets: AssetLine[]; onClose: () => void; onDone: (data: Data) => void }) {
  const [id] = useState(() => crypto.randomUUID());
  const funded = assets.filter((a) => !isZero(a.treasuryAccount));
  const [customer, setCustomer] = useState("");
  const [symbol, setSymbol] = useState(funded[0]?.symbol ?? "");
  const [amountText, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const chosen = assets.find((a) => a.symbol === symbol);
  const ready = customer.trim() !== "" && symbol !== "" && amountText.trim() !== "" && reason.trim() !== "" && code.length === 6;

  return (
    <Dialog title="Pay a customer from the treasury" onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => onDone(await payOut({ id, customer: customer.trim(), asset: symbol, amount: amountText.trim(), reason: reason.trim() }, code)))}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>Pay</button>
            </>}>
      <Field label="Customer (email or id)">
        <input className="input" value={customer} onChange={(e) => setCustomer(e.target.value)} autoFocus />
      </Field>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Asset">
          <select className="input" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
            {funded.length === 0 && <option value="">The treasury holds nothing</option>}
            {funded.map((a) => <option key={a.symbol} value={a.symbol}>{a.symbol}</option>)}
          </select>
        </Field>
        <Field label="Amount">
          <input className="input font-mono" inputMode="decimal" value={amountText} onChange={(e) => setAmount(e.target.value)} />
        </Field>
      </div>
      {chosen && <p className="m-0 text-muted">The treasury holds {amount(chosen.treasuryAccount, chosen.displayDecimals)} {chosen.symbol}.</p>}
      <Field label="Reason">
        <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} />
      </Field>
      <CodeInput value={code} onChange={setCode} label="Authenticator code" />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}

/** The latest moves of both kinds, newest first. The full trail is the audit log. */
function Moves() {
  const [moves, setMoves] = useState<Move[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    fetchMoves()
      .then((m) => { if (!cancelled) setMoves(m); })
      .catch((e: unknown) => { if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setError(e.message); });
    return () => { cancelled = true; };
  }, []);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!moves) return <p className="text-muted" aria-busy>Loading…</p>;
  return (
    <Table
      right={[3]}
      empty="No money has been moved."
      head={["When", "Move", "Asset", "Amount", "By", "Reason"]}
      rows={moves.map((m, i) => ({
        key: `${m.at}-${i}`,
        cells: [
          <time key="t" dateTime={m.at} className="font-mono text-muted">{new Date(m.at).toLocaleString([], { hour12: false })}</time>,
          <span key="k">{m.kind === "PAYOUT" ? `Treasury → ${m.to}` : "Fees → treasury"}</span>,
          <span key="a" className="font-semibold">{m.asset}</span>,
          <span key="n" className="font-mono">{m.amount}</span>,
          <span key="b" className="text-muted">{m.admin ?? "—"}</span>,
          <span key="r">{m.reason}</span>,
        ],
      }))}
    />
  );
}

/**
 * How far the sweep has got. The fee account lags its sweep interval by design, so a sweeper
 * that stopped is invisible without this -- the balances would stop moving and look like a
 * quiet day. Never swept is the one alarm on the page.
 */
function SweepStat({ sweptAt }: { sweptAt: string | null }) {
  const now = useNow();
  if (!sweptAt) {
    return <Stat label="Fees settled" value="Never" tone="warn"
                 note={<span role="alert">No fees have been settled yet.</span>} />;
  }
  const at = new Date(sweptAt);
  const minutes = Math.max(0, Math.round((now - at.getTime()) / 60_000));
  return (
    <Stat label="Fees settled" value={<time dateTime={sweptAt}>{at.toLocaleTimeString([], { hour12: false })}</time>}
          note={minutes < 1 ? "just now" : minutes < 120 ? `${minutes} min ago` : at.toLocaleDateString()} />
  );
}

/**
 * A text filter and a pager over one list of assets, shared by both views so they page and
 * filter alike. `empty` tells "no assets at all" from "none match".
 */
function useAssetList(assets: AssetLine[], empty: string) {
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const matching = q ? assets.filter((a) => a.symbol.toLowerCase().includes(q) || a.name.toLowerCase().includes(q)) : assets;
  const page = usePage(matching, PER_PAGE);
  const filter = (
    <input aria-label="Filter assets" value={query} placeholder="Filter assets" onChange={(e) => setQuery(e.target.value)}
           className="input w-full sm:w-56" />
  );
  const footer = (
    <>
      <span>{matching.length} asset{matching.length === 1 ? "" : "s"}</span>
      <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
    </>
  );
  return { filter, footer, rows: page.rows, empty: assets.length === 0 ? empty : "No asset matches." };
}

/** A reading taken now. The exchange's own accounts are their own columns, never customers'. */
function Held({ assets, onMove }: { assets: AssetLine[]; onMove?: (asset: AssetLine) => void }) {
  const list = useAssetList(assets, "Nothing is held yet.");
  return (
    <>
      <div className="flex items-center">{list.filter}</div>
      <Table
        right={[1, 2, 3, 4, 5, 6]}
        empty={list.empty}
        footer={list.footer}
        head={["Asset", "Customers available", "In order", "Customers total", "Holders", "Fee account", "Treasury", ...(onMove ? [""] : [])]}
        rows={list.rows.map((a) => ({
          key: a.symbol,
          cells: [
            <Symbol key="a" asset={a} />,
            <Num key="av" value={a.customerAvailable} places={a.displayDecimals} />,
            <Num key="lo" value={a.customerLocked} places={a.displayDecimals} muted />,
            <Num key="to" value={a.customerTotal} places={a.displayDecimals} />,
            <span key="h" className="font-mono text-muted">{a.holders.toLocaleString()}</span>,
            <Num key="fa" value={a.feeAccount} places={a.displayDecimals} />,
            <Num key="tr" value={a.treasuryAccount} places={a.displayDecimals} />,
            ...(onMove ? [isZero(a.feeAccount) ? <span key="m" />
              : <button key="m" className="btn btn-sm" onClick={() => onMove(a)}>Move to treasury</button>] : []),
          ],
        }))}
      />
    </>
  );
}

/** A total over a window: what customers paid in fees, less what makers were paid back. */
function Earned({ assets }: { assets: AssetLine[] }) {
  const [period, setPeriod] = useState<Period>("today");
  const list = useAssetList(assets, "Nothing has been earned yet.");
  // One asset at a time: stacking them would say they add up, and in coins they do not. It opens
  // on the first asset that has ever earned anything.
  const earning = assets.filter((a) => !isZero(a.periods.all.fees));
  const [charted, setCharted] = useState<string>(() => earning[0]?.symbol ?? assets[0]?.symbol ?? "");
  const chartedAsset = assets.find((a) => a.symbol === charted);

  return (
    <>
      {chartedAsset && (
        <section className="card px-[18px] py-4 flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="font-semibold">Fees per day</span>
            <select aria-label="Asset charted" value={charted} onChange={(e) => setCharted(e.target.value)} className="input">
              {assets.map((a) => <option key={a.symbol} value={a.symbol}>{a.symbol}</option>)}
            </select>
          </div>
          {/* Keyed, so a different asset is a fresh chart rather than the old bars under a new title. */}
          <RevenueChart key={chartedAsset.symbol} asset={chartedAsset.symbol} decimals={chartedAsset.displayDecimals} />
        </section>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        {list.filter}
        <div role="tablist" aria-label="Period" className="segmented">
          {PERIODS.map((p) => (
            <button key={p.id} role="tab" aria-selected={period === p.id} onClick={() => setPeriod(p.id)}>{p.label}</button>
          ))}
        </div>
      </div>

      <Table
        right={[1, 2, 3]}
        empty={list.empty}
        footer={list.footer}
        head={["Asset", "Fees earned", "Rebates paid", "Net"]}
        rows={list.rows.map((a) => ({
          key: a.symbol,
          cells: [
            <Symbol key="a" asset={a} />,
            <Num key="fe" value={a.periods[period].fees} places={a.displayDecimals} />,
            <Num key="re" value={a.periods[period].rebates} places={a.displayDecimals} muted />,
            <Num key="ne" value={a.periods[period].net} places={a.displayDecimals} strong />,
          ],
        }))}
      />
    </>
  );
}

function Symbol({ asset }: { asset: AssetLine }) {
  return <span><span className="font-semibold">{asset.symbol}</span> <span className="text-muted text-[13px]">{asset.name}</span></span>;
}

/** Zero is dimmed: a balance sheet is read by scanning for what is not zero. */
function Num({ value, places, muted = false, strong = false }: { value: string; places: number; muted?: boolean; strong?: boolean }) {
  const tone = isZero(value) ? "text-dim" : muted ? "text-muted" : strong ? "font-semibold" : "";
  return <span className={`font-mono ${tone}`}>{amount(value, places)}</span>;
}
