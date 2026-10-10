"use client";

import { useState } from "react";
import { DateRange } from "@/components/DateRange";
import { MorePager } from "@/components/Pager";
import { useLoad, useSearch } from "@/lib/search";
import { dayStart } from "@/lib/time";
import { fetchLedger, TYPES } from "./api";
import { LedgerTable } from "./LedgerTable";

const SIZE = 50;
const KEYS = ["customer", "asset", "type", "from", "to"] as const;

function load(key: string) {
  const q = new URLSearchParams(key);
  return fetchLedger({ customer: q.get("customer") ?? "", asset: q.get("asset") ?? "", type: q.get("type") ?? "",
                       from: dayStart(q.get("from")), to: dayStart(q.get("to"), 1) },
                     Math.max(0, Number(q.get("page")) || 0), SIZE);
}

/**
 * Every balance movement, newest first. The exchange's own accounts are found like anyone's, by
 * their email.
 */
export function Ledger() {
  const { query, page, go, key, filtered, clear } = useSearch(KEYS);
  const [draft, setDraft] = useState({ customer: query.customer, asset: query.asset });
  const { data, error } = useLoad(load, key);

  return (
    <>
      <form className="card px-3.5 py-3 flex flex-wrap items-center gap-2"
            onSubmit={(e) => { e.preventDefault(); go({ customer: draft.customer.trim(), asset: draft.asset.trim(), page: 0 }); }}>
        <input aria-label="Customer" value={draft.customer} placeholder="Customer email or id" className="input w-full sm:w-64"
               onChange={(e) => setDraft({ ...draft, customer: e.target.value })} />
        <input aria-label="Asset" value={draft.asset} placeholder="Asset" className="input w-full sm:w-28"
               onChange={(e) => setDraft({ ...draft, asset: e.target.value })} />
        <select aria-label="Movement" value={query.type} onChange={(e) => go({ type: e.target.value, page: 0 })} className="input w-full sm:w-auto">
          <option value="">Any movement</option>
          {Object.entries(TYPES).map(([id, label]) => <option key={id} value={id}>{label}</option>)}
        </select>
        <DateRange from={query.from} to={query.to} onChange={(r) => go({ ...r, page: 0 })} />
        <button className="btn">Search</button>
        {filtered && <button type="button" className="btn-link" onClick={() => { setDraft({ customer: "", asset: "" }); clear(); }}>Clear</button>}
      </form>

      {error ? <p role="alert" className="text-danger">{error}</p> : (
        <LedgerTable
          items={data?.items ?? []}
          empty={!data ? "Loading…" : filtered ? "No movement matches." : "Nothing has moved yet."}
          footer={data && (page > 0 || data.more) ? <MorePager page={page} more={data.more} onPage={(p) => go({ page: p })} /> : undefined}
        />
      )}
    </>
  );
}
