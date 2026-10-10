"use client";

import { useQuery } from "@tanstack/react-query";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useDescribe } from "@/components/ui/FormError";
import { useT } from "@/lib/i18n";
import { fetchSubAccounts, type SubAccount } from "../shared/api";
import { SelectFilter } from "../shared/filters";

/**
 * Which sub a section is about, kept in the URL (`?sub=`) so it survives a switch between
 * sections and a reload. With none chosen, the first.
 *
 * With none to choose, the section is still rendered, passed `null`: an account with no subs
 * yet should see the screen it is about to use, not a page that is only a sentence. The empty
 * picker is what says so -- the table below it is already empty, and saying it twice is noise.
 */
export function SubAccountPicker({ title, children }: {
  title: string; children: (sub: SubAccount | null, all: SubAccount[]) => React.ReactNode;
}) {
  const t = useT();
  const describe = useDescribe();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const subs = useQuery({ queryKey: ["sub-accounts"], queryFn: ({ signal }) => fetchSubAccounts(signal) });
  const wanted = Number(params.get("sub") ?? "");
  const chosen = subs.data?.find((s) => s.id === wanted) ?? subs.data?.[0] ?? null;

  if (subs.error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(subs.error)}</p>;
  if (subs.isPending) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{title}</h1>
        {/* Greyed rather than gone, so the screen keeps its shape while there is nothing to pick. */}
        <SelectFilter label={t("subAccounts.pick")} value={chosen ? String(chosen.id) : ""} disabled={!chosen}
                      onChange={(v) => router.replace(`${pathname}?sub=${v}`)}
                      options={chosen
                        ? subs.data.map((s) => ({ value: String(s.id), label: s.frozen ? `${s.label} · ${t("subAccounts.frozen")}` : s.label }))
                        : [{ value: "", label: "—" }]} />
      </div>
      {children(chosen, subs.data)}
    </div>
  );
}
