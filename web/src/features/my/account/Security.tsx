"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { useLogoutEverywhere } from "@/features/auth/session";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { localePath } from "@/lib/i18n/locales";
import { fetchIdentities, fetchMfa, unlinkGoogle } from "../shared/api";

export function Security() {
  const t = useT();
  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-xl font-semibold">{t("security.title")}</h1>
      {/* Flat on the page, rows split by hairlines (docs/STYLE.md "Surfaces"). */}
      <section className="divide-y divide-border rounded-lg border border-border">
        <TwoFactor />
        <Password />
        <Google />
        <Devices />
      </section>
    </div>
  );
}

function Row({ title, status, body, action, children }: {
  title: string; status?: React.ReactNode; body: React.ReactNode; action?: React.ReactNode; children?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-5 p-5">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold">{title}</h2>
            {status}
          </div>
          <div className="text-base text-muted">{body}</div>
        </div>
        {action && <div className="flex shrink-0 flex-wrap items-center gap-2">{action}</div>}
      </div>
      {children}
    </div>
  );
}

/** Status here; the setting itself is on its own page. */
function TwoFactor() {
  const t = useT();
  const locale = useLocale();
  const mfa = useQuery({ queryKey: ["mfa"], queryFn: ({ signal }) => fetchMfa(signal) });
  const on = mfa.data?.enabled ?? false;
  const added = mfa.data?.enabledAt ? new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(mfa.data.enabledAt)) : "—";
  return (
    <Row title={t("security.mfaTitle")}
         status={mfa.data && <Badge muted={!on}>{on ? t("security.on") : t("security.off")}</Badge>}
         body={on ? t("authenticator.added", { date: added }) : t("security.mfaOffBody")}
         action={<ManageLink href="/my/account/security/authenticator" />} />
  );
}

function ManageLink({ href }: { href: string }) {
  const t = useT();
  return (
    <LocaleLink href={href} className="inline-flex h-10 items-center rounded-lg border border-border bg-surface-raised px-4 text-base font-medium hover:border-border-strong">
      {t("security.manage")}
    </LocaleLink>
  );
}

function Password() {
  const t = useT();
  return <Row title={t("security.passwordTitle")} body={t("security.passwordBody")} action={<ManageLink href="/my/account/security/password" />} />;
}

function Google() {
  const t = useT();
  const locale = useLocale();
  const queryClient = useQueryClient();
  const identities = useQuery({ queryKey: ["identities"], queryFn: ({ signal }) => fetchIdentities(signal) });
  const [unlinking, setUnlinking] = useState(false);
  const unlink = useMutation({
    mutationFn: unlinkGoogle,
    onSuccess: () => { setUnlinking(false); queryClient.invalidateQueries({ queryKey: ["identities"] }); },
  });
  const google = identities.data?.find((i) => i.provider === "GOOGLE");
  const date = (iso: string) => new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(iso));

  return (
    <Row
      title={t("security.googleTitle")}
      status={identities.data && <Badge muted={!google}>{google ? t("security.linked") : t("security.notLinked")}</Badge>}
      body={google ? t("security.googleLinked", { email: google.email, date: date(google.linkedAt) }) : t("security.googleNotLinked")}
      action={google && !unlinking && <Button variant="secondary" onClick={() => setUnlinking(true)}>{t("security.unlink")}</Button>}
    >
      {unlinking && (
        <form className="flex max-w-sm flex-col gap-4 border-t border-border pt-5"
              onSubmit={(e) => { e.preventDefault(); unlink.mutate(String(new FormData(e.currentTarget).get("password") ?? "")); }}>
          <p className="text-base">{t("security.unlinkPrompt")}</p>
          <Field label={t("security.password")} name="password" type="password" autoComplete="current-password" autoFocus required />
          <FormError error={unlink.error} />
          <div className="flex gap-2">
            <Button type="submit" pending={unlink.isPending}>{t("security.unlink")}</Button>
            <Button type="button" variant="ghost" onClick={() => { unlink.reset(); setUnlinking(false); }}>{t("common.cancel")}</Button>
          </div>
        </form>
      )}
    </Row>
  );
}

function Devices() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const everywhere = useLogoutEverywhere();
  const [confirming, setConfirming] = useState(false);
  return (
    <Row title={t("security.devicesTitle")} body={t("security.devicesBody")}
         action={!confirming && <Button variant="secondary" onClick={() => setConfirming(true)}>{t("security.logoutAll")}</Button>}>
      {confirming && (
        <div role="alertdialog" aria-label={t("security.logoutAll")} className="flex flex-wrap items-center justify-end gap-3 border-t border-border pt-5 text-sm">
          <span className="text-muted">{t("security.logoutAllConfirm")}</span>
          {/* It ends this session too, so the login page is the only place left to go. */}
          <Button pending={everywhere.isPending}
                  onClick={() => everywhere.mutateAsync().finally(() => router.replace(localePath(locale, "/login")))}>{t("security.logoutAll")}</Button>
          <Button variant="ghost" onClick={() => setConfirming(false)}>{t("common.cancel")}</Button>
        </div>
      )}
    </Row>
  );
}
