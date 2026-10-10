"use client";

import { useState } from "react";
import { useT } from "@/lib/i18n";
import type { SubAccount } from "../shared/api";
import { SelectFilter } from "../shared/filters";
import { SearchFilters } from "../shared/SearchFilters";

export type StatusFilter = "" | "active" | "frozen";
export type SubFilter = { sub: string; status: StatusFilter };
export const NO_FILTER: SubFilter = { sub: "", status: "" };

export function matches(s: SubAccount, f: SubFilter): boolean {
  return (!f.sub || String(s.id) === f.sub) && (!f.status || (f.status === "frozen") === s.frozen);
}

/** Which sub a row belongs to; "" is every one of them. Shared by every sub-account list. */
export function SubSelect({ subs, value, onChange }: {
  subs: SubAccount[]; value: string; onChange: (sub: string) => void;
}) {
  const t = useT();
  return (
    <SelectFilter label={t("subAccounts.pick")} value={value} onChange={onChange}
                  options={[{ value: "", label: t("subAccounts.all") }, ...subs.map((s) => ({ value: String(s.id), label: s.label }))]} />
  );
}

/** Sub and status, for the account and asset lists. `children` is the row's right-hand end. */
export function SubAccountFilters({ subs, onApply, children }: {
  subs: SubAccount[]; onApply: (f: SubFilter) => void; children?: React.ReactNode;
}) {
  const t = useT();
  const [draft, setDraft] = useState<SubFilter>(NO_FILTER);

  return (
    <SearchFilters onSearch={() => onApply(draft)} actions={children}
                   onReset={() => { setDraft(NO_FILTER); onApply(NO_FILTER); }}>
      <SubSelect subs={subs} value={draft.sub} onChange={(sub) => setDraft((d) => ({ ...d, sub }))} />
      <SelectFilter<StatusFilter> label={t("subAccounts.status")} value={draft.status} onChange={(status) => setDraft((d) => ({ ...d, status }))}
                    options={[{ value: "", label: t("subAccounts.allStatuses") },
                              { value: "active", label: t("subAccounts.active") },
                              { value: "frozen", label: t("subAccounts.frozen") }]} />
    </SearchFilters>
  );
}
