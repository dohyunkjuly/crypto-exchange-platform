"use client";

import { useQuery } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { useSessionQuery } from "@/features/auth/session";
import { useLocale, useT } from "@/lib/i18n";
import { fetchTransfers, type SubAccount, type Transfer } from "../shared/api";
import { Pager, usePage } from "../shared/Pager";
import { SearchFilters } from "../shared/SearchFilters";
import { SelectFilter } from "../shared/filters";
import { SubSelect } from "./SubAccountFilters";

const PER_PAGE = 10;
type Direction = "" | "in" | "out";
type Filter = { sub: string; asset: string; direction: Direction };
const NO_FILTER: Filter = { sub: "", asset: "", direction: "" };

/**
 * Which rows the filters leave. A transfer names two accounts and no direction, so direction
 * here is relative to the account the sub filter names -- and to the master when it names
 * none, since that is whose page this is.
 */
export function matchesTransfer(x: Transfer, f: Filter, masterId: number): boolean {
  const about = f.sub ? Number(f.sub) : masterId;
  return (!f.sub || x.fromUserId === about || x.toUserId === about)
    && (!f.asset || x.asset === f.asset)
    && (!f.direction || (f.direction === "in" ? x.toUserId === about : x.fromUserId === about));
}

/** The last transfers in the family, newest first, each side named rather than numbered. */
export function Transfers({ subs }: { subs: SubAccount[] }) {
  const t = useT();
  const locale = useLocale();
  const me = useSessionQuery().data;
  const transfers = useQuery({ queryKey: ["transfers"], queryFn: ({ signal }) => fetchTransfers(signal) });
  const [draft, setDraft] = useState<Filter>(NO_FILTER);
  const [applied, setApplied] = useState<Filter>(NO_FILTER);

  const name = (id: number) => (id === me?.id ? t("subAccounts.master") : subs.find((s) => s.id === id)?.label ?? `#${id}`);
  const when = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  const all = transfers.data ?? [];
  // Only assets that have actually moved: a list of every listed coin is a list of empty answers.
  const assets = [...new Set(all.map((x) => x.asset))].sort();
  const page = usePage(all.filter((x) => matchesTransfer(x, applied, me?.id ?? 0)), PER_PAGE);

  return (
    <section className="flex flex-col gap-3">
      <SearchFilters onSearch={() => setApplied(draft)} onReset={() => { setDraft(NO_FILTER); setApplied(NO_FILTER); }}>
        <SubSelect subs={subs} value={draft.sub} onChange={(sub) => setDraft((d) => ({ ...d, sub }))} />
        <SelectFilter label={t("subAccounts.asset")} value={draft.asset} onChange={(asset) => setDraft((d) => ({ ...d, asset }))}
                      options={[{ value: "", label: t("subAccounts.all") }, ...assets.map((a) => ({ value: a, label: a }))]} />
        <SelectFilter<Direction> label={t("subAccounts.direction")} value={draft.direction} onChange={(direction) => setDraft((d) => ({ ...d, direction }))}
                      options={[{ value: "", label: t("subAccounts.all") },
                                { value: "in", label: t("subAccounts.transferIn") },
                                { value: "out", label: t("subAccounts.transferOut") }]} />
      </SearchFilters>

      {transfers.isPending ? <p className="py-6 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>
        : (
          <>
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full whitespace-nowrap text-base">
                <thead><tr className="border-b border-border text-left text-sm text-muted">
                  <th className="px-4 py-2 font-medium">{t("subAccounts.from")}</th>
                  <th className="px-4 py-2 font-medium">{t("subAccounts.to")}</th>
                  <th className="px-4 py-2 text-right font-medium">{t("subAccounts.amount")}</th>
                  <th className="px-4 py-2 text-right font-medium">{t("subAccounts.created")}</th>
                </tr></thead>
                <tbody>
                  {page.rows.map((x) => (
                    <tr key={x.transferId} className="border-b border-border last:border-0">
                      <td className="px-4 py-3">{name(x.fromUserId)}</td>
                      <td className="px-4 py-3">{name(x.toUserId)}</td>
                      <td className="num px-4 py-3 text-right">{x.amount} {x.asset}</td>
                      <td className="num px-4 py-3 text-right text-muted">{when(x.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {/* Below the columns, not instead of them: the header says what a transfer records. */}
            {page.rows.length === 0 && (
              <Empty className="-mt-3 rounded-b-lg border border-t-0 border-border">
                {t(all.length === 0 ? "subAccounts.noTransfers" : "subAccounts.noTransferMatch")}
              </Empty>
            )}
            <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
          </>
        )}
    </section>
  );
}
