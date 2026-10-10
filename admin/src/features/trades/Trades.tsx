"use client";

import { useState } from "react";
import { DateRange } from "@/components/DateRange";
import { MorePager } from "@/components/Pager";
import { useLoad, useSearch } from "@/lib/search";
import { dayStart } from "@/lib/time";
import { fetchTrades } from "./api";
import { TradesTable } from "./TradesTable";

const SIZE = 50;
const KEYS = ["customer", "symbol", "from", "to"] as const;

function load(key: string) {
  const q = new URLSearchParams(key);
  return fetchTrades({ customer: q.get("customer") ?? "", symbol: q.get("symbol") ?? "",
                       from: dayStart(q.get("from")), to: dayStart(q.get("to"), 1) },
                     Math.max(0, Number(q.get("page")) || 0), SIZE);
}

/** Every trade, newest first, both sides of each: who bought from whom. */
export function Trades() {
  const { query, page, go, key, filtered, clear } = useSearch(KEYS);
  const [draft, setDraft] = useState({ customer: query.customer, symbol: query.symbol });
  const { data, error } = useLoad(load, key);

  return (
    <>
      <form className="card px-3.5 py-3 flex flex-wrap items-center gap-2"
            onSubmit={(e) => { e.preventDefault(); go({ customer: draft.customer.trim(), symbol: draft.symbol.trim(), page: 0 }); }}>
        <input aria-label="Customer" value={draft.customer} placeholder="Customer email or id" className="input w-full sm:w-64"
               onChange={(e) => setDraft({ ...draft, customer: e.target.value })} />
        <input aria-label="Pair" value={draft.symbol} placeholder="Pair" className="input w-full sm:w-32"
               onChange={(e) => setDraft({ ...draft, symbol: e.target.value })} />
        <DateRange from={query.from} to={query.to} onChange={(r) => go({ ...r, page: 0 })} />
        <button className="btn">Search</button>
        {filtered && <button type="button" className="btn-link" onClick={() => { setDraft({ customer: "", symbol: "" }); clear(); }}>Clear</button>}
      </form>

      {error ? <p role="alert" className="text-danger">{error}</p> : (
        <TradesTable
          items={data?.items ?? []}
          empty={!data ? "Loading…" : filtered ? "No trade matches." : "No trades yet."}
          footer={data && (page > 0 || data.more) ? <MorePager page={page} more={data.more} onPage={(p) => go({ page: p })} /> : undefined}
        />
      )}
    </>
  );
}
