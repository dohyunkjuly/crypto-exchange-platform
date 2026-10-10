"use client";

import { useQuery } from "@tanstack/react-query";
import { BackLink } from "@/features/my/shared/BackLink";
import { Empty } from "@/components/ui/Empty";
import { useDescribe } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { price } from "@/lib/format";
import { fetchFollows, fetchLeaders } from "./api";
import { statusTone } from "./status";

/** The customer's copy accounts, open and closed: one row each, and the way into each. */
export function MyCopies() {
  const t = useT();
  const describe = useDescribe();
  const locale = useLocale();
  const follows = useQuery({ queryKey: ["copy-follows"], queryFn: ({ signal }) => fetchFollows(signal), refetchInterval: 10_000 });
  // The asset copy accounts are funded in; shared with the leaderboard's own request.
  const terms = useQuery({ queryKey: ["copy-leaders"], queryFn: ({ signal }) => fetchLeaders(signal), staleTime: 60_000 }).data?.terms;
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(iso));

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/copy" label={t("copy.leaders")} />
      <h1 className="text-xl font-semibold">{t("copy.myCopies")}</h1>
      {follows.error ? <p role="alert" className="py-8 text-center text-base text-muted">{describe(follows.error)}</p>
        : !follows.data ? <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>
        : (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full whitespace-nowrap text-base">
              <thead><tr className="border-b border-border text-left text-sm text-muted">
                <th className="px-4 py-3 font-medium">{t("copy.leader")}</th>
                <th className="px-4 py-3 font-medium">{t("copy.mode")}</th>
                <th className="px-4 py-3 font-medium">{t("copy.status")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("copy.value")}</th>
                <th className="hidden px-4 py-3 text-right font-medium sm:table-cell">{t("copy.started")}</th>
                <th className="px-4 py-3" />
              </tr></thead>
              <tbody>
                {follows.data.map((f) => (
                  <tr key={f.id} className="border-b border-border transition-colors last:border-0 hover:bg-surface-raised">
                    <td className="px-4 py-3 font-medium">{f.leaderName}</td>
                    <td className="px-4 py-3 text-muted">{t(f.mode === "FIXED_RATIO" ? "copy.fixedRatio" : "copy.fixedAmount")}</td>
                    <td className={`px-4 py-3 ${statusTone(f.status)}`}>{t(`copyStatus.${f.status}`)}</td>
                    <td className="num px-4 py-3 text-right">{terms ? `${price(f.value, 2)} ${terms.quoteAsset}` : "—"}</td>
                    <td className="num hidden px-4 py-3 text-right text-muted sm:table-cell">{date(f.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <LocaleLink href={`/copy/mine/${f.id}`} className="text-text underline-offset-4 hover:underline">{t("copy.details")}</LocaleLink>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {follows.data.length === 0 && <Empty>{t("copy.noCopies")}</Empty>}
          </div>
        )}
    </div>
  );
}
