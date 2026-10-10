"use client";

import { useQuery } from "@tanstack/react-query";
import { FormError } from "@/components/ui/FormError";
import { useLocale, useT, useTOptional } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { fetchKyc, fetchKycDetails, type KycStatus } from "../shared/api";

/** Statuses in which the user can (re)submit. Approved profiles are refused server-side (2016). */
export const canSubmit = (s: KycStatus | undefined) => !!s && (s.status === "NOT_STARTED" || s.status === "REJECTED");
const reviewing = (s: KycStatus | undefined) => !!s && (s.status === "PENDING" || s.status === "IN_REVIEW");

export function Identification() {
  const t = useT();
  const tOpt = useTOptional();
  const locale = useLocale();
  const kyc = useQuery({
    queryKey: ["kyc"],
    queryFn: ({ signal }) => fetchKyc(signal),
    // Review finishes elsewhere -- a reviewer on the admin app, or a provider webhook.
    refetchInterval: (q) => (reviewing(q.state.data) ? 3_000 : false),
  });
  const s = kyc.data;
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(iso));

  if (kyc.isPending) return <p className="text-base text-dim" aria-busy>{t("common.loading")}</p>;
  if (!s) return <FormError error={kyc.error} />;

  const verified = s.status === "APPROVED";
  const rejected = s.status === "REJECTED";
  const tone = verified ? "text-up" : rejected ? "text-danger" : "text-muted";
  // One line under the level: where you stand, and what to do about it if anything.
  const hint = verified ? (s.expiresAt ? t("kyc.expiresOn", { date: date(s.expiresAt) }) : t("kyc.verifiedHint"))
    : reviewing(s) ? t("kyc.inReview")
    : rejected ? `${t("kyc.rejected")}${s.rejectionReason ? ` · ${t("kyc.reason")}: ${s.rejectionReason}` : ""}`
    : t("kyc.unverifiedHint");

  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-xl font-semibold">{t("kyc.title")}</h1>

      {/* Where you stand and the one thing to do next, in a card, the way every exchange's
          identification page opens. The form is a step of its own, not the page. */}
      <section className="flex flex-wrap items-center justify-between gap-4 rounded-lg border border-border p-5">
        <div className="flex flex-col gap-1">
          <span className="text-sm text-muted">{t("kyc.level")}</span>
          <span className="flex flex-wrap items-center gap-2 text-lg font-semibold">
            {tOpt(`kycLevel.${s.level}`) ?? s.level}
            <span className={`rounded-full border border-border px-2 py-0.5 text-xs font-normal ${tone}`}>{tOpt(`kycStatus.${s.status}`) ?? s.status}</span>
          </span>
          <span className={`text-sm ${rejected ? "text-danger" : "text-muted"}`} role={rejected ? "alert" : undefined}>{hint}</span>
        </div>
        {canSubmit(s) && (
          <LocaleLink href="/my/account/identification/verify"
                      className="inline-flex h-10 items-center rounded-lg bg-text px-4 text-base font-medium text-bg hover:opacity-85">
            {rejected ? t("kyc.tryAgain") : t("kyc.verifyNow")}
          </LocaleLink>
        )}
      </section>

      {s.submittedAt && <PersonalInfo />}
    </div>
  );
}

/** A flat table on the page (docs/STYLE.md "Surfaces"). */
function PersonalInfo() {
  const t = useT();
  const locale = useLocale();
  const details = useQuery({ queryKey: ["kyc", "details"], queryFn: ({ signal }) => fetchKycDetails(signal) });
  const d = details.data;
  if (!d) return null;
  const country = (code?: string) => (code ? new Intl.DisplayNames([locale], { type: "region" }).of(code) ?? code : "—");
  const dob = d.dateOfBirth
    // A date, not an instant: formatted in UTC so it never shifts a day in a western timezone.
    ? new Intl.DateTimeFormat(locale, { dateStyle: "long", timeZone: "UTC" }).format(new Date(`${d.dateOfBirth}T00:00:00Z`))
    : "—";
  const rows: [string, string][] = [
    [t("kyc.fullName"), [d.legalFirstName, d.legalLastName].filter(Boolean).join(" ") || "—"],
    [t("kyc.dob"), dob],
    [t("kyc.nationality"), country(d.nationality)],
    [t("kyc.residence"), country(d.residenceCountry)],
  ];
  return (
    <section>
      <h2 className="mb-2 text-base font-semibold">{t("kyc.personal")}</h2>
      <table className="w-full text-base">
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k} className="border-b border-border">
              <td className="w-56 py-3 pr-4 text-muted">{k}</td>
              <td className="py-3">{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

