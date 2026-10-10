"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Pager } from "@/components/Pager";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { useNow } from "@/lib/now";
import { useAdmin } from "@/lib/session";
import { approveKyc, fetchQueue, rejectKyc, type KycStatus, type Level, type Queue, type Submission } from "./api";

const SIZE = 25;
const STATUSES: { id: KycStatus; label: string }[] = [
  { id: "PENDING", label: "Waiting" }, { id: "APPROVED", label: "Approved" },
  { id: "REJECTED", label: "Rejected" }, { id: "EXPIRED", label: "Expired" },
];

/**
 * The compliance queue. Waiting submissions oldest first -- a queue is worked from the back --
 * and each is decided in place, with a fresh authenticator code, because approving is what
 * opens trading. What is shown is what the customer typed; the documents are the provider's.
 */
export function Kyc() {
  const admin = useAdmin();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const status = (STATUSES.find((s) => s.id === params.get("status"))?.id ?? "PENDING") as KycStatus;
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const [data, setData] = useState<Queue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [deciding, setDeciding] = useState<{ item: Submission; verdict: "approve" | "reject" } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchQueue(status, page, SIZE)
      .then(setData)
      .catch((e: unknown) => { if (e instanceof AdminApiError && !e.notAuthenticated) setError(e.message); });
  }, [status, page]);
  useEffect(() => { load(); }, [load]);

  const go = (s: KycStatus, p: number) => {
    const q = new URLSearchParams();
    if (s !== "PENDING") q.set("status", s);
    if (p) q.set("page", String(p));
    router.replace(q.toString() ? `${pathname}?${q}` : pathname);
  };
  const canAct = admin?.role !== "VIEWER";
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;

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
          empty={status === "PENDING" ? "Nothing is waiting." : "Nothing here."}
          footer={<>
            <span>{data ? `${data.total} submission${data.total === 1 ? "" : "s"}` : "Loading…"}</span>
            <Pager current={page} pages={pages} onPage={(p) => go(status, p)} />
          </>}
          head={status === "PENDING"
            ? ["Waiting", "Customer", "Legal name", "Born", "Nationality", "Residence", ""]
            : ["Decided", "Customer", "Legal name", "Level", status === "REJECTED" ? "Reason" : "Expires", ""]}
          rows={(data?.items ?? []).map((s) => ({
            key: String(s.user.id),
            cells: status === "PENDING" ? [
              <Age key="w" iso={s.submittedAt} />,
              <Link key="e" href={`/users/${s.user.id}`} className="font-semibold text-text hover:text-accent">{s.user.email}</Link>,
              <span key="n">{s.legalFirstName} {s.legalLastName}</span>,
              <span key="b" className="font-mono">{s.dateOfBirth ?? "—"}</span>,
              <span key="na">{s.nationality ?? "—"}</span>,
              <span key="r">{s.residenceCountry ?? "—"}</span>,
              canAct ? (
                <span key="a" className="flex justify-end gap-2">
                  <button className="btn btn-sm btn-primary" onClick={() => setDeciding({ item: s, verdict: "approve" })}>Approve</button>
                  <button className="btn btn-sm btn-danger" onClick={() => setDeciding({ item: s, verdict: "reject" })}>Reject</button>
                </span>
              ) : <span key="a" />,
            ] : [
              <span key="w" className="font-mono text-muted">{(s.reviewedAt ?? "").slice(0, 16).replace("T", " ") || "—"}</span>,
              <Link key="e" href={`/users/${s.user.id}`} className="font-semibold text-text hover:text-accent">{s.user.email}</Link>,
              <span key="n">{s.legalFirstName} {s.legalLastName}</span>,
              <span key="l" className={`pill ${status === "APPROVED" ? "pill-ok" : "pill-off"}`}>{s.level}</span>,
              <span key="x" className="text-muted">{status === "REJECTED" ? (s.rejectionReason ?? "—") : (s.expiresAt?.slice(0, 10) ?? "never")}</span>,
              <span key="a" />,
            ],
          }))}
        />
      )}

      {deciding && (
        <Decide item={deciding.item} verdict={deciding.verdict} onClose={() => setDeciding(null)}
                onDone={(message) => { setDeciding(null); setNotice(message); load(); }} />
      )}
    </>
  );
}

/** How long it has waited -- what the queue is sorted by, and what a reviewer feels. Over a day
 *  it turns amber, over three red: a queue is judged by its oldest entry. */
function Age({ iso }: { iso: string | null }) {
  const now = useNow();
  if (!iso) return <span className="text-dim">—</span>;
  const hours = Math.floor((now - new Date(iso).getTime()) / 3_600_000);
  const label = hours < 1 ? "just now" : hours < 48 ? `${hours}h` : `${Math.floor(hours / 24)}d`;
  const tone = hours >= 72 ? "pill-bad" : hours >= 24 ? "pill-warn" : "pill-off";
  return <span className={`pill ${tone} font-mono`} title={iso}>{label}</span>;
}

/**
 * Approve to a level with an expiry, or reject with a reason the customer will read. Either way
 * the code comes last, after the decision is typed: the form is the what, the code is the proof.
 */
function Decide({ item, verdict, onClose, onDone }: {
  item: Submission; verdict: "approve" | "reject"; onClose: () => void; onDone: (message: string) => void;
}) {
  const [level, setLevel] = useState<Level>("BASIC");
  const [months, setMonths] = useState<string>("12");
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const ready = code.length === 6 && (verdict === "approve" || reason.trim().length > 0);

  const submit = () => run(async () => {
    if (verdict === "approve") {
      const expiresAt = months === "" ? null : new Date(Date.now() + Number(months) * 30 * 86_400_000).toISOString();
      await approveKyc(item.user.id, level, expiresAt, code);
      onDone(`${item.user.email} approved to ${level}.`);
    } else {
      await rejectKyc(item.user.id, reason.trim(), code);
      onDone(`${item.user.email} rejected.`);
    }
  });

  return (
    <Dialog title={`${verdict === "approve" ? "Approve" : "Reject"} ${item.user.email}`} onClose={onClose}
            onSubmit={() => ready && !busy && submit()}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" disabled={!ready || busy} className={`btn ${verdict === "approve" ? "btn-primary" : "btn-danger"}`}>
                {verdict === "approve" ? "Approve" : "Reject"}
              </button>
            </>}>
      <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-md border border-border bg-bg px-3.5 py-3">
        <dt className="text-muted">Legal name</dt><dd className="m-0">{item.legalFirstName} {item.legalLastName}</dd>
        <dt className="text-muted">Born</dt><dd className="m-0 font-mono">{item.dateOfBirth ?? "—"}</dd>
        <dt className="text-muted">Nationality / residence</dt><dd className="m-0">{item.nationality ?? "—"} / {item.residenceCountry ?? "—"}</dd>
      </dl>

      {verdict === "approve" ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Level">
            <select value={level} onChange={(e) => setLevel(e.target.value as Level)} className="input">
              <option value="BASIC">BASIC — trading</option>
              <option value="INTERMEDIATE">INTERMEDIATE — API keys</option>
              <option value="ADVANCED">ADVANCED</option>
            </select>
          </Field>
          <Field label="Valid for">
            <select value={months} onChange={(e) => setMonths(e.target.value)} className="input">
              <option value="12">12 months</option>
              <option value="24">24 months</option>
              <option value="">Does not lapse</option>
            </select>
          </Field>
        </div>
      ) : (
        <Field label="Reason, as the customer will read it">
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} className="input" autoFocus />
        </Field>
      )}

      <CodeInput value={code} onChange={setCode} label="Authenticator code" />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
