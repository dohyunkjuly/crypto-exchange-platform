"use client";

import { useState } from "react";
import { DateRange } from "@/components/DateRange";
import { Pager } from "@/components/Pager";
import { useLoad, useSearch } from "@/lib/search";
import { dayStart } from "@/lib/time";
import { fetchOrders } from "./api";
import { OrdersTable } from "./OrdersTable";

const SIZE = 50;
const KEYS = ["customer", "symbol", "side", "state", "from", "to"] as const;

function load(key: string) {
  const q = new URLSearchParams(key);
  const get = (k: string) => q.get(k) ?? "";
  return fetchOrders({ customer: get("customer"), symbol: get("symbol"), side: get("side"), state: get("state"),
                       from: dayStart(q.get("from")), to: dayStart(q.get("to"), 1) },
                     Math.max(0, Number(get("page")) || 0), SIZE);
}

/**
 * Every customer's orders, newest first, searched on the server. Read only: staff never cancel or
 * change a customer's order.
 */
export function Orders() {
  const { query, page, go, key, filtered, clear } = useSearch(KEYS);
  const [draft, setDraft] = useState({ customer: query.customer, symbol: query.symbol });
  const { data, error } = useLoad(load, key);
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;

  return (
    <>
      <form className="card px-3.5 py-3 flex flex-wrap items-center gap-2"
            onSubmit={(e) => { e.preventDefault(); go({ customer: draft.customer.trim(), symbol: draft.symbol.trim(), page: 0 }); }}>
        <input aria-label="Customer" value={draft.customer} placeholder="Customer email or id" className="input w-full sm:w-64"
               onChange={(e) => setDraft({ ...draft, customer: e.target.value })} />
        <input aria-label="Pair" value={draft.symbol} placeholder="Pair" className="input w-full sm:w-32"
               onChange={(e) => setDraft({ ...draft, symbol: e.target.value })} />
        <select aria-label="Side" value={query.side} onChange={(e) => go({ side: e.target.value, page: 0 })} className="input w-full sm:w-auto">
          <option value="">Any side</option>
          <option value="BUY">Buy</option>
          <option value="SELL">Sell</option>
        </select>
        <select aria-label="State" value={query.state} onChange={(e) => go({ state: e.target.value, page: 0 })} className="input w-full sm:w-auto">
          <option value="">Any state</option>
          <option value="open">Open</option>
          <option value="closed">Closed</option>
        </select>
        <DateRange from={query.from} to={query.to} onChange={(r) => go({ ...r, page: 0 })} />
        <button className="btn">Search</button>
        {filtered && <button type="button" className="btn-link" onClick={() => { setDraft({ customer: "", symbol: "" }); clear(); }}>Clear</button>}
      </form>

      {error ? <p role="alert" className="text-danger">{error}</p> : (
        <OrdersTable
          items={data?.items ?? []}
          empty={filtered ? "No order matches." : "No orders yet."}
          footer={<>
            <span>{data ? `${data.total.toLocaleString()} order${data.total === 1 ? "" : "s"}` : "Loading…"}</span>
            <Pager current={page} pages={pages} onPage={(p) => go({ page: p })} />
          </>}
        />
      )}
    </>
  );
}
