"use client";

import { useState } from "react";
import { MorePager } from "@/components/Pager";
import { useLoad } from "@/lib/search";
import { fetchLedger } from "./api";
import { LedgerTable } from "./LedgerTable";

const SIZE = 20;

function load(key: string) {
  const q = new URLSearchParams(key);
  return fetchLedger({ customer: q.get("customer") ?? "", asset: "", type: "", from: "", to: "" }, Number(q.get("page")), SIZE);
}

/** One customer's statement across every asset, on their file. */
export function CustomerLedger({ userId }: { userId: number }) {
  const [page, setPage] = useState(0);
  const { data, error } = useLoad(load, new URLSearchParams({ customer: String(userId), page: String(page) }).toString());
  if (error) return <p role="alert" className="text-danger">{error}</p>;
  return (
    <LedgerTable
      customer={false}
      items={data?.items ?? []}
      empty={data ? "Nothing has moved." : "Loading…"}
      footer={data && (page > 0 || data.more) ? <MorePager page={page} more={data.more} onPage={setPage} /> : undefined}
    />
  );
}
