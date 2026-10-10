"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { countryOptions } from "@/lib/i18n/countries";
import { localePath } from "@/lib/i18n/locales";
import { fetchKyc, submitKyc } from "../shared/api";
import { BackLink } from "../shared/BackLink";
import { canSubmit } from "./Identification";

/**
 * The verification step on its own page, like creating an API key: the identification page
 * says where you stand and offers the button; this is what the button opens. Submitting sends
 * you back there, where the status carries on.
 */
export function VerifyIdentity() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const countries = useMemo(() => countryOptions(locale), [locale]);
  const today = new Date().toISOString().slice(0, 10);
  const [nationality, setNationality] = useState("");
  const [residence, setResidence] = useState("");
  const kyc = useQuery({ queryKey: ["kyc"], queryFn: ({ signal }) => fetchKyc(signal) });
  const back = localePath(locale, "/my/account/identification");
  const submit = useMutation({
    mutationFn: submitKyc,
    onSuccess: (status) => {
      queryClient.setQueryData(["kyc"], status);
      queryClient.invalidateQueries({ queryKey: ["kyc"] });
      router.replace(back);
    },
  });
  // Nothing to submit from here: already in review, or verified. Sent back rather than shown a form the server would refuse.
  if (kyc.data && !canSubmit(kyc.data)) {
    router.replace(back);
    return null;
  }

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/my/account/identification" label={t("kyc.back")} />
      <h1 className="text-xl font-semibold">{t("kyc.verifyTitle")}</h1>
      <form
        className="flex max-w-lg flex-col gap-5 rounded-lg border border-border p-5"
        onSubmit={(e) => {
          e.preventDefault();
          const d = new FormData(e.currentTarget);
          submit.mutate({
            legalFirstName: String(d.get("first")).trim(),
            legalLastName: String(d.get("last")).trim(),
            dateOfBirth: String(d.get("dob")),
            nationality, residenceCountry: residence,
          });
        }}
      >
        <p className="text-sm text-muted">{t("kyc.formHint")}</p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("kyc.firstName")} name="first" autoComplete="given-name" required autoFocus />
          <Field label={t("kyc.lastName")} name="last" autoComplete="family-name" required />
        </div>
        <Field label={t("kyc.dob")} name="dob" type="date" max={today} required />
        <div className="grid gap-4 sm:grid-cols-2">
          <Country label={t("kyc.nationality")} value={nationality} onChange={setNationality} options={countries} autoComplete="off" />
          <Country label={t("kyc.residence")} value={residence} onChange={setResidence} options={countries} autoComplete="country" />
        </div>
        <FormError error={submit.error} />
        <div className="flex gap-2">
          <Button type="submit" pending={submit.isPending}>{t("kyc.submit")}</Button>
          <Button type="button" variant="ghost" onClick={() => router.replace(back)}>{t("common.cancel")}</Button>
        </div>
      </form>
    </div>
  );
}

function Country({ label, value, onChange, options, autoComplete }: {
  label: string; value: string; onChange: (v: string) => void;
  options: { code: string; name: string }[]; autoComplete: string;
}) {
  const t = useT();
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-sm font-medium text-muted">{label}</span>
      <select required value={value} onChange={(e) => onChange(e.target.value)} autoComplete={autoComplete}
              className="h-10 rounded-lg border border-border bg-surface-raised px-3 text-base text-text outline-none focus:border-border-strong">
        <option value="" disabled>{t("kyc.choose")}</option>
        {options.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
      </select>
    </label>
  );
}
