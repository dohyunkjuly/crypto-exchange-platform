"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { fetchSubAccounts, setSubAccountFrozen, type SubAccount } from "../shared/api";
import { Pager, usePage } from "../shared/Pager";
import { matches, NO_FILTER, SubAccountFilters, type SubFilter } from "./SubAccountFilters";
import { CreateSubAccount } from "./CreateSubAccount";

const MAX = 20;
const PER_PAGE = 10;

/**
 * The subs, one row each: freeze, unfreeze and transfer from here, with the filters above them.
 * Creating is a dialog rather than a page -- one field, and landing back on a list that has
 * changed is the whole point of it.
 */
export function SubAccounts() {
  const t = useT();
  const describe = useDescribe();
  const subs = useQuery({ queryKey: ["sub-accounts"], queryFn: ({ signal }) => fetchSubAccounts(signal) });
  const [creating, setCreating] = useState(false);
  const [applied, setApplied] = useState<SubFilter>(NO_FILTER);

  const all = subs.data ?? [];
  const full = all.length >= MAX;
  const page = usePage(all.filter((s) => matches(s, applied)), PER_PAGE);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{t("menu.subAccountManagement")}</h1>
        <LocaleLink href="/my/sub-accounts/transfer" className="inline-flex h-10 items-center rounded-lg border border-border px-4 text-base font-medium hover:border-border-strong">
          {t("subAccounts.transfer")}
        </LocaleLink>
      </div>

      <SubAccountFilters subs={all} onApply={setApplied}>
        <span className="text-base text-muted">{t("subAccounts.count", { n: all.length })}</span>
        <Button variant="secondary" className="h-9" disabled={full} title={full ? t("subAccounts.limit") : undefined}
                onClick={() => setCreating(true)}>
          + {t("subAccounts.create")}
        </Button>
      </SubAccountFilters>

      {creating && <CreateSubAccount onClose={() => setCreating(false)} />}

      {subs.error ? <p role="alert" className="py-8 text-center text-base text-muted">{describe(subs.error)}</p>
        : subs.isPending ? <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>
        : <List subs={page.rows} empty={t(all.length === 0 ? "subAccounts.empty" : "subAccounts.noMatch")} />}

      <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
    </div>
  );
}

/** `empty` distinguishes an account with no subs at all from a filter that matched none. */
function List({ subs, empty }: { subs: SubAccount[]; empty: string }) {
  const t = useT();
  const locale = useLocale();
  const queryClient = useQueryClient();
  const [freezing, setFreezing] = useState<SubAccount | null>(null);
  const frozen = useMutation({
    mutationFn: setSubAccountFrozen,
    onSettled: () => { setFreezing(null); queryClient.invalidateQueries({ queryKey: ["sub-accounts"] }); },
  });
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));

  return (
    <div className="flex flex-col gap-3">
      <FormError error={frozen.error} />
      {freezing && (
        <ConfirmDialog title={t("subAccounts.freezeTitle")} message={t("subAccounts.freezeConfirm")} pending={frozen.isPending}
                       onConfirm={() => frozen.mutate({ id: freezing.id, frozen: true })} onClose={() => setFreezing(null)} />
      )}
      {/* Cards on a phone: five columns of an account row do not survive a narrow screen, and a
          sideways scroll hides exactly the one column anyone came for. */}
      <ul className="flex flex-col gap-3 sm:hidden">
        {subs.map((s) => (
          <li key={s.id} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <div className="flex items-center justify-between gap-3">
              <span className="text-base font-medium">{s.label}</span>
              <span className={`rounded-full border border-border px-2 py-0.5 text-xs ${s.frozen ? "text-danger" : "text-up"}`}>
                {t(s.frozen ? "subAccounts.frozen" : "subAccounts.active")}
              </span>
            </div>
            <span className="break-all text-sm text-muted">{s.email}</span>
            <span className="num text-sm text-dim">{date(s.createdAt)}</span>
            <div className="mt-1 flex items-center gap-4 border-t border-border pt-3 text-base">
              {!s.frozen && (
                <LocaleLink href={`/my/sub-accounts/transfer?to=${s.id}`} className="text-text underline-offset-4 hover:underline">
                  {t("subAccounts.transfer")}
                </LocaleLink>
              )}
              {s.frozen
                ? <button onClick={() => frozen.mutate({ id: s.id, frozen: false })} className="text-text underline-offset-4 hover:underline">{t("subAccounts.unfreeze")}</button>
                : <button onClick={() => setFreezing(s)} className="text-muted underline-offset-4 hover:text-text hover:underline">{t("subAccounts.freeze")}</button>}
            </div>
          </li>
        ))}
      </ul>

      <div className="hidden overflow-x-auto rounded-lg border border-border sm:block">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="px-4 py-3 font-medium">{t("subAccounts.label")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.email")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.status")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.created")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("subAccounts.actions")}</th>
          </tr></thead>
          <tbody>
            {subs.map((s) => (
              <tr key={s.id} className="border-b border-border transition-colors last:border-0 hover:bg-surface-raised">
                <td className="px-4 py-3 font-medium">{s.label}</td>
                <td className="px-4 py-3 text-muted">{s.email}</td>
                <td className={`px-4 py-3 ${s.frozen ? "text-danger" : "text-up"}`}>{t(s.frozen ? "subAccounts.frozen" : "subAccounts.active")}</td>
                <td className="num px-4 py-3 text-muted">{date(s.createdAt)}</td>
                <td className="px-4 py-3">
                  <span className="flex items-center justify-end gap-4">
                    {/* Nothing may reach a frozen sub, so it is offered no transfer. */}
                    {!s.frozen && (
                      <LocaleLink href={`/my/sub-accounts/transfer?to=${s.id}`} className="text-text underline-offset-4 hover:underline">
                        {t("subAccounts.transfer")}
                      </LocaleLink>
                    )}
                    {/* Unfreezing is undoing; only freezing asks. */}
                    {s.frozen
                      ? <button onClick={() => frozen.mutate({ id: s.id, frozen: false })} className="text-text underline-offset-4 hover:underline">{t("subAccounts.unfreeze")}</button>
                      : <button onClick={() => setFreezing(s)} className="text-muted underline-offset-4 hover:text-text hover:underline">{t("subAccounts.freeze")}</button>}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {/* Below the columns rather than instead of them, and one element for both layouts: two
          copies of the same line is two things for a screen reader to read out. */}
      {subs.length === 0 && <Empty className="rounded-lg border border-border sm:-mt-3 sm:rounded-t-none sm:border-t-0">{empty}</Empty>}
    </div>
  );
}
