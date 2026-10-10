"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Pager } from "@/components/Pager";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { fetchUsers, type Page, type Status, type Summary } from "./api";
import { StatusWord } from "./StatusWord";

const SIZE = 25;

/**
 * Customers, newest first, searched on the server: unlike assets, this list is not bounded.
 * The search and the page live in the URL, so a refresh keeps them and a link means the one you
 * sent. Sub-accounts are not listed -- a sub is reached through its master.
 */
export function Users() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const q = params.get("q") ?? "";
  const status = (params.get("status") ?? "") as Status | "";
  const page = Math.max(0, Number(params.get("page") ?? "0") || 0);
  const [draft, setDraft] = useState(q);
  const [data, setData] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchUsers(q, status, page, SIZE)
      .then((d) => { if (!cancelled) setData(d); })
      .catch((e: unknown) => {
        if (!cancelled && e instanceof AdminApiError && !e.notAuthenticated) setError(e.message);
      });
    return () => { cancelled = true; };
  }, [q, status, page]);

  const go = (next: { q?: string; status?: string; page?: number }) => {
    const p = new URLSearchParams();
    const nq = next.q ?? q;
    const ns = next.status ?? status;
    const np = next.page ?? 0;
    if (nq) p.set("q", nq);
    if (ns) p.set("status", ns);
    if (np) p.set("page", String(np));
    const s = p.toString();
    router.replace(s ? `${pathname}?${s}` : pathname);
  };

  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;

  return (
    <>
      <form className="card px-3.5 py-3 flex flex-wrap items-center gap-2"
            onSubmit={(e) => { e.preventDefault(); go({ q: draft.trim(), page: 0 }); }}>
        <input aria-label="Search by email or id" value={draft} onChange={(e) => setDraft(e.target.value)}
               placeholder="Email or id" className="input w-full sm:w-80" />
        <select aria-label="Status" value={status} onChange={(e) => go({ status: e.target.value, page: 0 })} className="input w-full sm:w-auto">
          <option value="">Any status</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Frozen</option>
          <option value="CLOSED">Closed</option>
        </select>
        <button className="btn">Search</button>
        {(q || status) && (
          <button type="button" className="btn-link" onClick={() => { setDraft(""); go({ q: "", status: "", page: 0 }); }}>Clear</button>
        )}
      </form>

      {error ? <p role="alert" className="text-danger">{error}</p> : (
        <Table
          empty={q || status ? "No customer matches." : "No customers yet."}
          head={["Customer", "Status", "KYC", "2FA", "Email", "Joined"]}
          right={[5]}
          footer={<>
            <span>{data ? `${data.total.toLocaleString()} customer${data.total === 1 ? "" : "s"}` : "Loading…"}</span>
            <Pager current={page} pages={pages} onPage={(p) => go({ page: p })} />
          </>}
          rows={(data?.items ?? []).map((u: Summary) => ({
            key: String(u.id),
            cells: [
              <span key="e" className="flex flex-col leading-snug">
                <Link href={`/users/${u.id}`} className="font-semibold text-text hover:text-accent">{u.email}</Link>
                {u.name && <span className="text-[12px] text-muted">{u.name}</span>}
              </span>,
              <StatusWord key="s" user={u} />,
              <span key="k" className={u.kycLevel === "NONE" ? "text-dim" : ""}>{u.kycLevel}</span>,
              <span key="m" className={u.mfaEnabled ? "" : "text-dim"}>{u.mfaEnabled ? "On" : "Off"}</span>,
              <span key="v" className={u.emailVerified ? "" : "text-dim"}>{u.emailVerified ? "Verified" : "Unverified"}</span>,
              <span key="c" className="font-mono text-muted">{u.createdAt.slice(0, 10)}</span>,
            ],
          }))}
        />
      )}
    </>
  );
}
