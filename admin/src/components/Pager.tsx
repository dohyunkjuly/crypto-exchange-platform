"use client";

import { useState } from "react";

/**
 * Paging over rows already in hand. The page is clamped rather than reset, so a filter that
 * leaves fewer pages lands on the last one that exists instead of on an empty table.
 * Copied from web/, not shared: the two apps deploy apart and must not import across.
 */
export function usePage<T>(rows: T[], perPage: number) {
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(rows.length / perPage));
  const current = Math.min(page, pages - 1);
  return { current, pages, setPage, rows: rows.slice(current * perPage, (current + 1) * perPage) };
}

/** For a table's footer. Nothing on a single page: a pager that cannot move is furniture. */
export function Pager({ current, pages, onPage }: { current: number; pages: number; onPage: (page: number) => void }) {
  if (pages <= 1) return null;
  return (
    <div className="flex items-center gap-2">
      <span className="font-mono mr-1">{current + 1} / {pages}</span>
      <button className="btn btn-sm" disabled={current === 0} onClick={() => onPage(current - 1)}>Previous</button>
      <button className="btn btn-sm" disabled={current === pages - 1} onClick={() => onPage(current + 1)}>Next</button>
    </div>
  );
}

/**
 * For a list that knows whether more follows but not how much: the trades and the ledger, where
 * counting every row on every page costs more than the page.
 */
export function MorePager({ page, more, onPage }: { page: number; more: boolean; onPage: (page: number) => void }) {
  if (page === 0 && !more) return null;
  return (
    <div className="flex items-center gap-2 ml-auto">
      <span className="font-mono mr-1">Page {page + 1}</span>
      <button className="btn btn-sm" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous</button>
      <button className="btn btn-sm" disabled={!more} onClick={() => onPage(page + 1)}>Next</button>
    </div>
  );
}
