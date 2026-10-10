"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { DateRange } from "@/components/DateRange";
import { Pager } from "@/components/Pager";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { fetchActions, fetchAudit, type Entry, type Filter, type Page } from "./api";

const SIZE = 50;
const KEYS: (keyof Filter)[] = ["adminId", "action", "targetType", "targetId", "from", "to"];

/**
 * Every admin write, newest first, read back (docs/reference/admin.md). The filters live in the
 * URL, so "everything ops@ did on Tuesday" is a link. Before and after are the fields that
 * changed, shown as stored -- a reader who wants them pretty has the JSON.
 */
export function Audit() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const applied = Object.fromEntries(KEYS.map((k) => [k, params.get(k) ?? ""])) as Filter;
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const [draft, setDraft] = useState<Filter>(applied);
  const [actions, setActions] = useState<string[]>([]);
  const [data, setData] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { fetchActions().then(setActions).catch(() => setActions([])); }, []);
  useEffect(() => {
    let cancelled = false;
    fetchAudit(applied, page, SIZE)
      .then((d) => { if (!cancelled) setData(d); })
      .catch((e: unknown) => { if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setError(e.message); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the filter is the URL; params is the dependency
  }, [params, page]);

  const go = (f: Filter, p: number) => {
    const q = new URLSearchParams();
    KEYS.forEach((k) => { if (f[k]) q.set(k, f[k]); });
    if (p) q.set("page", String(p));
    router.replace(q.toString() ? `${pathname}?${q}` : pathname);
  };
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;

  return (
    <>
      <form className="card px-3.5 py-3 flex flex-wrap items-center gap-2" onSubmit={(e) => { e.preventDefault(); go(draft, 0); }}>
        <select aria-label="Action" value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })} className="input w-full sm:w-auto">
          <option value="">Any action</option>
          {actions.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <input aria-label="Admin id" placeholder="Admin id" value={draft.adminId}
               onChange={(e) => setDraft({ ...draft, adminId: e.target.value.replace(/\D/g, "") })} className="input w-full sm:w-24" />
        <select aria-label="Target type" value={draft.targetType} onChange={(e) => setDraft({ ...draft, targetType: e.target.value })} className="input w-full sm:w-auto">
          <option value="">Any target</option>
          <option value="user">Customer</option>
          <option value="admin">Admin</option>
          <option value="api_key">API key</option>
        </select>
        <input aria-label="Target id" placeholder="Target id" value={draft.targetId}
               onChange={(e) => setDraft({ ...draft, targetId: e.target.value })} className="input w-full sm:w-36" />
        <DateRange from={draft.from} to={draft.to} onChange={(r) => setDraft({ ...draft, ...r })} />
        <button className="btn">Search</button>
        {KEYS.some((k) => applied[k]) && (
          <button type="button" className="btn-link" onClick={() => {
            const none = Object.fromEntries(KEYS.map((k) => [k, ""])) as Filter; setDraft(none); go(none, 0);
          }}>Clear</button>
        )}
      </form>

      {error ? <p role="alert" className="text-danger">{error}</p> : (
        <Table
          empty="Nothing recorded."
          head={["When (UTC)", "Admin", "Action", "Target", "Change", "IP"]}
          footer={<>
            <span>{data ? `${data.total.toLocaleString()} entr${data.total === 1 ? "y" : "ies"}` : "Loading…"}</span>
            <Pager current={page} pages={pages} onPage={(p) => go(applied, p)} />
          </>}
          rows={(data?.items ?? []).map((e: Entry) => ({
            key: String(e.id),
            cells: [
              <span key="w" className="font-mono text-muted">{e.createdAt.slice(0, 19).replace("T", " ")}</span>,
              e.adminEmail ? <span key="a">{e.adminEmail}</span> : <span key="a" className="text-dim">—</span>,
              <span key="ac" className={`pill ${e.action.endsWith("FAILED") ? "pill-bad" : "pill-off"} font-mono`}>{e.action}</span>,
              <TargetLink key="t" entry={e} />,
              <Change key="c" entry={e} />,
              <span key="ip" className="font-mono text-muted">{e.ipAddress}</span>,
            ],
          }))}
        />
      )}
    </>
  );
}

/** A customer id is a link to the customer; anything else is what it is. */
function TargetLink({ entry: e }: { entry: Entry }) {
  if (!e.targetType) return <span className="text-dim">—</span>;
  const label = `${e.targetType} ${e.targetId ?? ""}`.trim();
  return e.targetType === "user" && e.targetId
    ? <Link href={`/users/${e.targetId}`} className="font-mono">{label}</Link>
    : <span className="font-mono text-muted">{label}</span>;
}

/** before → after, as stored; a login has neither and shows nothing. */
function Change({ entry: e }: { entry: Entry }) {
  if (!e.before && !e.after) return <span className="text-dim">—</span>;
  return (
    <span className="flex max-w-md flex-col gap-0.5 whitespace-normal break-all font-mono text-[12px]">
      {e.before && <span className="text-dim" title="before">{e.before}</span>}
      {e.after && <span title="after">{e.after}</span>}
    </span>
  );
}
