"use client";

import { useState } from "react";
import { Pager } from "@/components/Pager";
import { useLoad } from "@/lib/search";
import { fetchOrders } from "./api";
import { OrdersTable } from "./OrdersTable";

const SIZE = 20;

function load(key: string) {
  const q = new URLSearchParams(key);
  return fetchOrders({ customer: q.get("customer") ?? "", symbol: "", side: "", state: q.get("state") ?? "", from: "", to: "" },
                     Number(q.get("page")), SIZE);
}

/** One customer's open orders, or their finished ones, on their file. */
export function CustomerOrders({ userId, state }: { userId: number; state: "open" | "closed" }) {
  const [page, setPage] = useState(0);
  const { data, error } = useLoad(load, new URLSearchParams({ customer: String(userId), state, page: String(page) }).toString());
  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;
  return (
    <OrdersTable
      customer={false}
      items={data?.items ?? []}
      empty={data ? (state === "open" ? "No open orders." : "No finished orders.") : "Loading…"}
      footer={<>
        <span>{data ? `${data.total.toLocaleString()} order${data.total === 1 ? "" : "s"}` : "Loading…"}</span>
        <Pager current={page} pages={pages} onPage={setPage} />
      </>}
    />
  );
}
