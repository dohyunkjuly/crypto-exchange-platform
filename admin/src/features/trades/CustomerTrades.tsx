"use client";

import { useState } from "react";
import { MorePager } from "@/components/Pager";
import { useLoad } from "@/lib/search";
import { fetchTrades } from "./api";
import { TradesTable } from "./TradesTable";

const SIZE = 20;

function load(key: string) {
  const q = new URLSearchParams(key);
  return fetchTrades({ customer: q.get("customer") ?? "", symbol: "", from: "", to: "" }, Number(q.get("page")), SIZE);
}

/** One customer's trades on their file, from their side. */
export function CustomerTrades({ userId }: { userId: number }) {
  const [page, setPage] = useState(0);
  const { data, error } = useLoad(load, new URLSearchParams({ customer: String(userId), page: String(page) }).toString());
  if (error) return <p role="alert" className="text-danger">{error}</p>;
  return (
    <TradesTable
      of={userId}
      items={data?.items ?? []}
      empty={data ? "No trades." : "Loading…"}
      footer={data && (page > 0 || data.more) ? <MorePager page={page} more={data.more} onPage={setPage} /> : undefined}
    />
  );
}
