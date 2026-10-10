"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Confirm } from "@/components/Confirm";
import { Fact } from "@/components/Fact";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { amount, isZero } from "@/lib/money";
import { useAdmin } from "@/lib/session";
import { CustomerLedger } from "../ledger/CustomerLedger";
import { CustomerOrders } from "../orders/CustomerOrders";
import { CustomerTrades } from "../trades/CustomerTrades";
import {
  fetchUser, freezeUser, resumeTrading, revokeApiKey, revokeSessions, suspendTrading, unfreezeUser, type Detail, type KeyLine,
} from "./api";
import { FeesAndLimits } from "./FeesAndLimits";
import { StatusWord } from "./StatusWord";

type Tab = "balances" | "open" | "history" | "trades" | "ledger" | "limits" | "subs" | "keys" | "sessions";
type Pending =
  | { kind: "freeze" } | { kind: "unfreeze" } | { kind: "suspend" } | { kind: "resume" } | { kind: "sessions" }
  | { kind: "key"; key: KeyLine };

/**
 * One customer: who they are, what they hold, and what an OPERATOR may do about it. Actions
 * sit with the identity block; each goes through the one confirm dialog and refetches when
 * done, so the screen shows what the server did rather than what the button hoped.
 */
export function UserDetail({ id }: { id: number }) {
  const admin = useAdmin();
  const [data, setData] = useState<Detail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("balances");
  const [pending, setPending] = useState<Pending | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchUser(id)
      .then(setData)
      .catch((e: unknown) => {
        if (e instanceof AdminApiError && !e.notAuthenticated) setError(e.message);
      });
  }, [id]);
  useEffect(() => { load(); }, [load]);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!data || !admin) return <p className="text-muted" aria-busy>Loading…</p>;

  const u = data.user;
  const canAct = admin.role !== "VIEWER";
  const done = (message: string) => { setPending(null); setNotice(message); load(); };

  // Orders, trades and the ledger are fetched when their tab opens, a page at a time: no count on
  // those, since counting them is the one cost their lists avoid.
  const tabs: { id: Tab; label: string; count?: number }[] = [
    { id: "balances", label: "Balances", count: data.balances.length },
    { id: "open", label: "Open orders" },
    { id: "history", label: "Order history" },
    { id: "trades", label: "Trades" },
    { id: "ledger", label: "Ledger" },
    { id: "limits", label: "Fees & limits" },
    ...(u.type === "PRIMARY" ? [{ id: "subs" as Tab, label: "Sub-accounts", count: data.subAccounts.length }] : []),
    { id: "keys", label: "API keys", count: data.apiKeys.filter((k) => !k.revokedAt).length },
    { id: "sessions", label: "Sessions", count: data.sessions.length },
  ];

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}

      <section className="card px-4 py-4 sm:px-5 sm:py-[18px] flex flex-col sm:flex-row items-start gap-4 sm:gap-5">
        <span aria-hidden className={`hidden sm:flex w-14 h-14 shrink-0 rounded-full items-center justify-center text-xl font-bold ${
          u.status === "SUSPENDED" ? "bg-danger-bg text-danger" : "bg-[#ddf4ff] text-accent"}`}>{u.email.slice(0, 1).toUpperCase()}</span>

        <div className="flex-1 min-w-0 flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="m-0 text-lg font-semibold break-all">{u.email}</h1>
            <StatusWord user={u} masterSuspended={!!data.master?.tradingSuspendedAt} />
            {(u.frozenReason ?? u.tradingSuspendedReason) && (
              <span className="text-[13px] text-muted">{u.frozenReason ?? u.tradingSuspendedReason}</span>
            )}
          </div>
          <dl className="m-0 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4">
            <Fact label="Name">{u.name ?? <span className="text-dim">—</span>}</Fact>
            <Fact label="KYC">{u.kycLevel}{u.kycVerifiedUntil && <span className="text-muted"> until {u.kycVerifiedUntil.slice(0, 7)}</span>}</Fact>
            <Fact label="2FA">{u.mfaEnabled ? "On" : <span className="text-muted">Off</span>}</Fact>
            <Fact label="Email">{u.emailVerified ? "Verified" : <span className="text-muted">Unverified</span>}</Fact>
            <Fact label="Customer id"><span className="font-mono">#{u.id}</span></Fact>
            <Fact label={data.master ? "Master" : "Joined"}>
              {data.master
                ? <Link href={`/users/${data.master.id}`}>{data.master.email}</Link>
                : <span className="font-mono">{u.createdAt.slice(0, 10)}</span>}
            </Fact>
          </dl>
        </div>

        {canAct && (
          <div className="shrink-0 flex flex-col items-stretch gap-2 w-full sm:w-44">
            {u.status === "SUSPENDED"
              ? <button className="btn btn-primary" onClick={() => setPending({ kind: "unfreeze" })}>Unfreeze</button>
              : <button className="btn btn-danger" onClick={() => setPending({ kind: "freeze" })}>Freeze</button>}
            {u.tradingSuspendedAt
              ? <button className="btn" onClick={() => setPending({ kind: "resume" })}>Resume trading</button>
              : u.status === "ACTIVE" && <button className="btn" onClick={() => setPending({ kind: "suspend" })}>Suspend trading</button>}
            <button className="btn" onClick={() => setPending({ kind: "sessions" })} disabled={data.sessions.length === 0}>
              Log out everywhere
            </button>
          </div>
        )}
      </section>

      <div role="tablist" className="tabs">
        {tabs.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} className="tab" onClick={() => setTab(t.id)}>
            {t.label}{t.count !== undefined && <span className="count">{t.count}</span>}
          </button>
        ))}
      </div>

      {tab === "balances" && (
        <Table right={[1, 2, 3]} empty="Holds nothing." head={["Asset", "Available", "In order", "Total"]}
               rows={data.balances.map((b) => ({
                 key: b.asset,
                 cells: [<span key="a" className="font-semibold">{b.asset}</span>,
                   <Num key="av" value={b.available} places={b.displayDecimals} />,
                   <Num key="lo" value={b.locked} places={b.displayDecimals} />,
                   <Num key="to" value={b.total} places={b.displayDecimals} />],
               }))} />
      )}

      {/* Keyed by customer: another customer's file starts on page one. */}
      {tab === "open" && <CustomerOrders key={u.id} userId={u.id} state="open" />}
      {tab === "history" && <CustomerOrders key={u.id} userId={u.id} state="closed" />}
      {tab === "trades" && <CustomerTrades key={u.id} userId={u.id} />}
      {tab === "ledger" && <CustomerLedger key={u.id} userId={u.id} />}
      {tab === "limits" && <FeesAndLimits key={u.id} userId={u.id} masterId={data.master?.id ?? null} canAct={canAct} />}

      {tab === "subs" && (
        <Table empty="No sub-accounts." head={["Label", "Email", "Status", "Created"]}
               rows={data.subAccounts.map((s) => ({
                 key: String(s.id),
                 cells: [<Link key="l" href={`/users/${s.id}`} className="font-semibold text-text hover:text-accent">{s.name ?? "—"}</Link>,
                   <span key="e" className="text-muted">{s.email}</span>,
                   <StatusWord key="s" user={s} masterSuspended={!!u.tradingSuspendedAt} />,
                   <span key="c" className="font-mono text-muted">{s.createdAt.slice(0, 10)}</span>],
               }))} />
      )}

      {tab === "keys" && (
        <Table empty="No API keys." head={["Label", "Owner", "Key", "Read", "Trade", "Last used", "Expires", "State", ""]}
               rows={data.apiKeys.map((k) => {
                 const owner = k.userId === u.id ? "this account" : data.subAccounts.find((s) => s.id === k.userId)?.name ?? `#${k.userId}`;
                 const live = !k.revokedAt && (!k.expiresAt || new Date(k.expiresAt) > new Date());
                 return {
                   key: String(k.id),
                   cells: [<span key="l" className={live ? "font-medium" : "text-dim"}>{k.label}</span>,
                     <span key="o" className="text-muted">{owner}</span>,
                     <code key="k" className="font-mono text-muted">{k.apiKey.slice(0, 8)}…</code>,
                     <span key="r" className={k.canRead ? "" : "text-dim"}>{k.canRead ? "Yes" : "—"}</span>,
                     <span key="t" className={k.canTrade ? "" : "text-dim"}>{k.canTrade ? "Yes" : "—"}</span>,
                     <span key="u" className="font-mono text-muted">{k.lastUsedAt ? k.lastUsedAt.slice(0, 10) : "never"}</span>,
                     <span key="x" className="font-mono text-muted">{k.expiresAt ? k.expiresAt.slice(0, 10) : "never"}</span>,
                     <span key="s" className={`pill ${live ? "pill-ok" : "pill-off"}`}>{k.revokedAt ? "Revoked" : live ? "Active" : "Expired"}</span>,
                     canAct && live
                       ? <button key="d" onClick={() => setPending({ kind: "key", key: k })} className="btn btn-sm btn-danger">Revoke</button>
                       : <span key="d" />],
                 };
               })} />
      )}

      {tab === "sessions" && (
        <Table empty="Not signed in anywhere." head={["Started", "Last seen", "IP", "Country", "Device", "Expires"]}
               rows={data.sessions.map((s) => ({
                 key: String(s.id),
                 cells: [<span key="c" className="font-mono">{s.createdAt.slice(0, 16).replace("T", " ")}</span>,
                   <span key="l" className="font-mono text-muted">{s.lastSeenAt ? s.lastSeenAt.slice(0, 16).replace("T", " ") : "—"}</span>,
                   <span key="i" className="font-mono">{s.ipAddress ?? "—"}</span>,
                   <span key="co">{s.countryCode ?? "—"}</span>,
                   <span key="d" className="max-w-xs truncate text-muted" title={s.userAgent ?? ""}>{s.userAgent ?? "—"}</span>,
                   <span key="x" className="font-mono text-muted">{s.expiresAt.slice(0, 10)}</span>],
               }))} />
      )}

      {pending?.kind === "freeze" && (
        <Confirm title="Freeze this account" reasonLabel="Reason" confirmLabel="Freeze" tone="danger"
                 body={`${u.email} will be logged out everywhere and unable to trade${
                   u.type === "PRIMARY" && data.subAccounts.length ? `, and so will its ${data.subAccounts.length} sub-account(s)` : ""}. The reason is kept on the account.`}
                 onConfirm={async (reason) => { const r = await freezeUser(u.id, reason);
                   done(`Frozen. ${r.sessionsRevoked} session(s) ended${r.subsAffected.length ? `, ${r.subsAffected.length} sub-account(s) frozen with it` : ""}.`); }}
                 onClose={() => setPending(null)} />
      )}
      {pending?.kind === "unfreeze" && (
        <Confirm title="Unfreeze this account" confirmLabel="Unfreeze"
                 body={`${u.email} may sign in and trade again. A sub-account frozen along with it is released; one frozen on its own stays frozen.`}
                 onConfirm={async () => { const r = await unfreezeUser(u.id);
                   done(`Unfrozen${r.subsAffected.length ? `, with ${r.subsAffected.length} sub-account(s)` : ""}.`); }}
                 onClose={() => setPending(null)} />
      )}
      {pending?.kind === "suspend" && (
        <Confirm title="Suspend trading" reasonLabel="Reason" confirmLabel="Suspend trading" tone="danger"
                 body={`${u.email} can still log in, see balances and cancel orders, but cannot place or amend one, create a trading API key or move funds between sub-accounts${
                   u.type === "PRIMARY" && data.subAccounts.length ? `. Its ${data.subAccounts.length} sub-account(s) are stopped with it` : ""}. Orders already on the book stay there.`}
                 onConfirm={async (reason) => { await suspendTrading(u.id, reason); done("Trading suspended."); }}
                 onClose={() => setPending(null)} />
      )}
      {pending?.kind === "resume" && (
        <Confirm title="Resume trading" confirmLabel="Resume trading"
                 body={`${u.email} may place orders again${u.type === "PRIMARY" && data.subAccounts.length ? ", and so may its sub-accounts" : ""}.`}
                 onConfirm={async () => { await resumeTrading(u.id); done("Trading resumed."); }}
                 onClose={() => setPending(null)} />
      )}
      {pending?.kind === "sessions" && (
        <Confirm title="Log out everywhere" confirmLabel="Log out" tone="danger"
                 body={`Every session of ${u.email} ends now. They can sign in again; nothing else changes.`}
                 onConfirm={async () => { const r = await revokeSessions(u.id); done(`${r.sessionsRevoked} session(s) ended.`); }}
                 onClose={() => setPending(null)} />
      )}
      {pending?.kind === "key" && (
        <Confirm title="Revoke API key" confirmLabel="Revoke" tone="danger"
                 body={`"${pending.key.label}" stops working within a minute, including on connections already open. This cannot be undone; a new key would have to be created.`}
                 onConfirm={async () => { await revokeApiKey(u.id, pending.key.id, pending.key.userId); done("API key revoked."); }}
                 onClose={() => setPending(null)} />
      )}
    </>
  );
}

function Num({ value, places }: { value: string; places: number }) {
  return <span className={`font-mono ${isZero(value) ? "text-dim" : ""}`}>{amount(value, places)}</span>;
}
