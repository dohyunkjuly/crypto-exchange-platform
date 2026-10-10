"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { useT } from "@/lib/i18n";

/**
 * Paging over rows already in hand. The page is clamped rather than reset, so a filter that
 * leaves fewer pages lands on the last one that exists instead of on an empty table.
 */
export function usePage<T>(rows: T[], perPage: number) {
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(rows.length / perPage));
  const current = Math.min(page, pages - 1);
  return { current, pages, setPage, rows: rows.slice(current * perPage, (current + 1) * perPage) };
}

/**
 * Renders nothing on a single page: a pager that cannot move is furniture. `pages` is the
 * total when the rows are in hand; a server-paged list has no count, so it passes `hasNext`
 * instead -- true while the page came back full -- and the label is the page alone.
 */
export function Pager({ current, pages, hasNext, onPage }: { current: number; pages?: number; hasNext?: boolean; onPage: (page: number) => void }) {
  const t = useT();
  const last = pages !== undefined ? current >= pages - 1 : !hasNext;
  if (current === 0 && last) {
    return null;
  }
  return (
    <div className="flex items-center justify-center gap-4">
      <Button variant="secondary" className="h-9" disabled={current === 0} onClick={() => onPage(current - 1)}>
        {t("common.prevPage")}
      </Button>
      <span className="num text-base text-muted">
        {pages !== undefined ? t("common.pageOf", { n: current + 1, total: pages }) : t("common.page", { n: current + 1 })}
      </span>
      <Button variant="secondary" className="h-9" disabled={last} onClick={() => onPage(current + 1)}>
        {t("common.nextPage")}
      </Button>
    </div>
  );
}
