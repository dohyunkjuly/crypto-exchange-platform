"use client";

import { useQuery } from "@tanstack/react-query";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { fetchSubAccounts } from "../shared/api";
import { Transfers } from "./TransferTable";

/** Every transfer in the family, and the button to make one. */
export function SubTransfers() {
  const t = useT();
  const subs = useQuery({ queryKey: ["sub-accounts"], queryFn: ({ signal }) => fetchSubAccounts(signal) });
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{t("menu.subTransfers")}</h1>
        <LocaleLink href="/my/sub-accounts/transfer" className="inline-flex h-10 items-center rounded-lg bg-text px-4 text-base font-medium text-bg hover:opacity-85">
          {t("subAccounts.transfer")}
        </LocaleLink>
      </div>
      <Transfers subs={subs.data ?? []} />
    </div>
  );
}
