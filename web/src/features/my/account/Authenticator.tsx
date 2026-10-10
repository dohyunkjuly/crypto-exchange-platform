"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeInput } from "@/components/ui/CodeInput";
import { Dialog } from "@/components/ui/Dialog";
import { FormError } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { confirmTotp, disableMfa, fetchMfa, startTotp, type TotpEnrolment } from "../shared/api";
import { BackLink } from "../shared/BackLink";
import { CopyButton } from "../shared/CopyButton";
import { QrCode } from "../shared/QrCode";

const codeFrom = (form: HTMLFormElement) => String(new FormData(form).get("code") ?? "").trim();

/**
 * The authenticator's page: one card. On, it shows when it was added and a
 * delete that asks for a current code. There is no edit: replacing it is removing it and adding a
 * new one. Off, setting one up is the QR code, the setup key under it, and a code -- nothing else.
 * The recovery codes the server returns on confirming are deliberately not shown.
 */
export function Authenticator() {
  const t = useT();
  const locale = useLocale();
  const queryClient = useQueryClient();
  const mfa = useQuery({ queryKey: ["mfa"], queryFn: ({ signal }) => fetchMfa(signal) });
  const [enrolment, setEnrolment] = useState<TotpEnrolment | null>(null);
  const [removing, setRemoving] = useState(false);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["mfa"] });

  const start = useMutation({ mutationFn: startTotp, onSuccess: setEnrolment });
  const confirm = useMutation({ mutationFn: confirmTotp, onSuccess: () => { setEnrolment(null); refresh(); } });
  const remove = useMutation({ mutationFn: disableMfa, onSuccess: () => { setRemoving(false); refresh(); } });
  const on = mfa.data?.enabled ?? false;
  const added = mfa.data?.enabledAt
    ? new Intl.DateTimeFormat(locale, { dateStyle: "long" }).format(new Date(mfa.data.enabledAt))
    : "—";

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/my/account/security" label={t("password.back")} />
      <h1 className="text-xl font-semibold">{t("authenticator.title")}</h1>

      {mfa.isPending ? <p className="text-base text-dim" aria-busy>{t("common.loading")}</p> : (
        <section className="flex flex-col gap-6 rounded-lg border border-border p-5">
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-start gap-3">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" aria-hidden className="mt-0.5 shrink-0 text-muted">
                <path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6l-8-3Z" />
                <path d="M12 9v6M9.4 10.5l5.2 3M14.6 10.5l-5.2 3" strokeLinecap="round" />
              </svg>
              <div className="flex flex-col gap-1">
                <span className="text-base font-medium">{t("authenticator.name")}</span>
                <span className="text-base text-muted">{on ? t("authenticator.added", { date: added }) : t("authenticator.notSet")}</span>
              </div>
            </div>
            {on ? (
              <button type="button" aria-label={t("authenticator.remove")} onClick={() => setRemoving(true)} className="text-muted hover:text-text">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6" />
                </svg>
              </button>
            ) : !enrolment && (
              <Button onClick={() => start.mutate()} pending={start.isPending}>{t("authenticator.enable")}</Button>
            )}
          </div>
          {!enrolment && <FormError error={start.error} />}

          {enrolment && !on && (
            <form className="flex flex-col items-center gap-5 border-t border-border pt-6"
                  onSubmit={(e) => { e.preventDefault(); confirm.mutate(codeFrom(e.currentTarget)); }}>
              <QrCode value={enrolment.provisioningUri} label={t("security.qrLabel")} />
              <div className="flex items-center gap-3">
                <code className="num break-all text-base">{enrolment.secret}</code>
                <CopyButton text={enrolment.secret} />
              </div>
              <div className="w-full max-w-xs">
                <CodeInput name="code" label={t("mfa.code")} autoFocus disabled={confirm.isPending} onComplete={(code) => confirm.mutate(code)} />
              </div>
              <FormError error={confirm.error} />
              <div className="flex gap-2">
                <Button type="submit" pending={confirm.isPending}>{t("authenticator.confirm")}</Button>
                <Button type="button" variant="ghost" onClick={() => { confirm.reset(); setEnrolment(null); }}>{t("common.cancel")}</Button>
              </div>
            </form>
          )}
        </section>
      )}

      {removing && (
        <Dialog title={t("authenticator.remove")} onClose={() => { remove.reset(); setRemoving(false); }}>
          <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); remove.mutate(codeFrom(e.currentTarget)); }}>
            <CodeInput name="code" label={t("mfa.code")} autoFocus disabled={remove.isPending} onComplete={(code) => remove.mutate(code)} />
            <FormError error={remove.error} />
            <Button type="submit" block pending={remove.isPending}>{t("authenticator.confirm")}</Button>
          </form>
        </Dialog>
      )}
    </div>
  );
}
