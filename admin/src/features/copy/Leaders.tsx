"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Pager } from "@/components/Pager";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { useAdmin } from "@/lib/session";
import { decideLeader, fetchLeaders, setLeaderProfitShare, type Decision, type Leader, type Leaders as Page, type LeaderStatus } from "./api";

const SIZE = 25;
const STATUSES: { id: LeaderStatus; label: string }[] = [
  { id: "ACTIVE", label: "Leading" }, { id: "SUSPENDED", label: "Suspended" }, { id: "CLOSED", label: "Closed" },
];
const DECISIONS: Record<Decision, { label: string; done: string; danger: boolean; reason: boolean }> = {
  suspend: { label: "Suspend", done: "suspended", danger: true, reason: true },
  reinstate: { label: "Reinstate", done: "reinstated", danger: false, reason: false },
};
/** What each state can become. A closed leader comes back only by leading again. */
const OPEN: Record<LeaderStatus, Decision[]> = { ACTIVE: ["suspend"], SUSPENDED: ["reinstate"], CLOSED: [] };

/**
 * Who trades for others. A customer leads the moment they fund; a leader is suspended and
 * reinstated here, each with a fresh authenticator code, because followers' money moves on what a leader does.
 * Suspending closes every follower's copy account (docs/reference/copy-trading.md).
 *
 * <p>A leader may also be given a profit share of their own, or put back on the exchange's;
 * staff only, never the leader. It applies to whoever follows from then on.
 */
export function Leaders() {
  const admin = useAdmin();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const status = (STATUSES.find((s) => s.id === params.get("status"))?.id ?? "ACTIVE") as LeaderStatus;
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const [data, setData] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [deciding, setDeciding] = useState<{ leader: Leader; decision: Decision } | null>(null);
  const [rating, setRating] = useState<Leader | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchLeaders(status, page, SIZE)
      .then(setData)
      .catch((e: unknown) => { if (e instanceof AdminApiError && !e.notAuthenticated) setError(e.message); });
  }, [status, page]);
  useEffect(() => { load(); }, [load]);

  const go = (s: LeaderStatus, p: number) => {
    const q = new URLSearchParams();
    if (s !== "ACTIVE") q.set("status", s);
    if (p) q.set("page", String(p));
    router.replace(q.toString() ? `${pathname}?${q}` : pathname);
  };
  const canAct = admin?.role !== "VIEWER";
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;
  const decided = status === "SUSPENDED";
  // A rate matters while the leader can still be followed, or may be again.
  const rated = status !== "CLOSED";

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}

      <div role="tablist" className="tabs">
        {STATUSES.map((s) => (
          <button key={s.id} role="tab" aria-selected={status === s.id} className="tab" onClick={() => go(s.id, 0)}>
            {s.label}{status === s.id && data && <span className="count">{data.total}</span>}
          </button>
        ))}
      </div>

      {error ? <p role="alert" className="text-danger">{error}</p> : (
        <Table
          empty="Nothing here."
          footer={<>
            <span>{data ? `${data.total} leader${data.total === 1 ? "" : "s"}` : "Loading…"}</span>
            <Pager current={page} pages={pages} onPage={(p) => go(status, p)} />
          </>}
          head={[decided ? "Decided" : "Since", "Customer", "Name", decided ? "Reason" : "Followers",
            ...(rated ? ["Profit share"] : []), ""]}
          rows={(data?.items ?? []).map((l) => ({
            key: l.id,
            cells: [
              <span key="w" className="font-mono text-muted">{when(decided ? l.reviewedAt : l.appliedAt)}</span>,
              <Link key="e" href={`/users/${l.userId}`} className="font-semibold text-text hover:text-accent">{l.email}</Link>,
              <span key="n">{l.displayName}</span>,
              decided ? <span key="x" className="text-muted">{l.reviewReason ?? "—"}</span>
                : <span key="x" className="font-mono">{l.followers}</span>,
              ...(rated ? [l.profitShareBps === null
                ? <span key="p" className="text-muted">Default</span>
                : <span key="p" className="font-mono">{l.profitShareBps / 100}%</span>] : []),
              canAct ? (
                <span key="a" className="flex justify-end gap-2">
                  {rated && <button className="btn btn-sm" onClick={() => setRating(l)}>Profit share</button>}
                  {OPEN[l.status].map((d) => (
                    <button key={d} className={`btn btn-sm ${DECISIONS[d].danger ? "btn-danger" : "btn-primary"}`}
                            onClick={() => setDeciding({ leader: l, decision: d })}>{DECISIONS[d].label}</button>
                  ))}
                </span>
              ) : <span key="a" />,
            ],
          }))}
        />
      )}

      {rating && (
        <ProfitShare leader={rating} onClose={() => setRating(null)}
                     onDone={(message) => { setRating(null); setNotice(message); load(); }} />
      )}
      {deciding && (
        <Decide leader={deciding.leader} decision={deciding.decision} onClose={() => setDeciding(null)}
                onDone={(message) => { setDeciding(null); setNotice(message); load(); }} />
      )}
    </>
  );
}

const when = (iso: string | null) => (iso ?? "").slice(0, 16).replace("T", " ") || "—";

/** One decision. The reason is for the audit row and the next admin; the customer is never told why. */
function Decide({ leader, decision, onClose, onDone }: {
  leader: Leader; decision: Decision; onClose: () => void; onDone: (message: string) => void;
}) {
  const { label, done, danger, reason: needsReason } = DECISIONS[decision];
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const ready = code.length === 6 && (!needsReason || reason.trim().length > 0);

  const submit = () => run(async () => {
    await decideLeader(leader.id, decision, needsReason ? reason.trim() : null, code);
    onDone(`${leader.displayName} ${done}.`);
  });

  return (
    <Dialog title={`${label} ${leader.displayName}`} onClose={onClose}
            onSubmit={() => ready && !busy && submit()}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" disabled={!ready || busy} className={`btn ${danger ? "btn-danger" : "btn-primary"}`}>{label}</button>
            </>}>
      <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-md border border-border bg-bg px-3.5 py-3">
        <dt className="text-muted">Customer</dt><dd className="m-0">{leader.email}</dd>
        <dt className="text-muted">Followers</dt><dd className="m-0 font-mono">{leader.followers}</dd>
      </dl>

      {decision === "suspend" && leader.followers > 0 && (
        <p role="alert" className="m-0 text-danger">
          Closes {leader.followers} copy account{leader.followers === 1 ? "" : "s"}. Reinstating does not reopen them.
        </p>
      )}
      {needsReason && (
        <Field label="Reason, for the audit log">
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} className="input" autoFocus />
        </Field>
      )}

      <CodeInput value={code} onChange={setCode} label="Authenticator code" />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}

/** This leader's own rate, or empty for the exchange's. A whole or decimal percentage, 0 to 100. */
function ProfitShare({ leader, onClose, onDone }: { leader: Leader; onClose: () => void; onDone: (message: string) => void }) {
  const [percent, setPercent] = useState(leader.profitShareBps === null ? "" : String(leader.profitShareBps / 100));
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const bps = percent.trim() === "" ? null : Math.round(Number(percent) * 100);
  const valid = bps === null || (/^\d+(\.\d{1,2})?$/.test(percent.trim()) && bps >= 0 && bps <= 10_000);
  const ready = code.length === 6 && valid;

  const submit = () => run(async () => {
    await setLeaderProfitShare(leader.id, bps, code);
    onDone(bps === null ? `${leader.displayName} is back on the default profit share.` : `${leader.displayName}'s profit share is ${bps / 100}%.`);
  });

  return (
    <Dialog title={`Profit share for ${leader.displayName}`} onClose={onClose}
            onSubmit={() => ready && !busy && submit()}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" disabled={!ready || busy} className="btn btn-primary">Save</button>
            </>}>
      <p className="m-0 text-muted">Applies to whoever follows from now on. Existing followers keep the rate they started with.</p>
      <Field label="Profit share (%), empty for the default">
        <input aria-label="Profit share" value={percent} onChange={(e) => setPercent(e.target.value)} className="input font-mono" autoFocus />
      </Field>
      <CodeInput value={code} onChange={setCode} label="Authenticator code" />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
