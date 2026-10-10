"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeInput } from "@/components/ui/CodeInput";
import { Dialog } from "@/components/ui/Dialog";
import { FormError } from "@/components/ui/FormError";
import { PasswordField } from "@/components/ui/PasswordField";
import { MIN_PASSWORD_LENGTH } from "@/features/auth/api";
import { useT } from "@/lib/i18n";
import { changePassword, fetchMfa } from "../shared/api";
import { BackLink } from "../shared/BackLink";

/**
 * Changing the password from inside the account: the new password twice, then
 * a pop-up for the one proof the server wants -- the authenticator code when 2FA is on, the
 * current password when not. No emailed code; that is the logged-out reset. This session stays,
 * every other one ends.
 */
export function ChangePassword() {
  const t = useT();
  const mfa = useQuery({ queryKey: ["mfa"], queryFn: ({ signal }) => fetchMfa(signal) });
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [asking, setAsking] = useState(false);
  const [done, setDone] = useState(false);
  const change = useMutation({
    mutationFn: changePassword,
    onSuccess: () => { setAsking(false); setPassword(""); setAgain(""); setDone(true); },
  });
  const longEnough = password.length >= MIN_PASSWORD_LENGTH;
  const matches = again.length > 0 && again === password;
  const mfaOn = !!mfa.data?.enabled;
  const send = (proof: { currentPassword?: string; mfaCode?: string }) => change.mutate({ newPassword: password, ...proof });

  if (mfa.isPending) return <p className="text-base text-dim" aria-busy>{t("common.loading")}</p>;

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/my/account/security" label={t("password.back")} />
      <h1 className="text-xl font-semibold">{t("password.title")}</h1>

      <form className="flex w-full max-w-md flex-col gap-5 self-center"
            onSubmit={(e) => { e.preventDefault(); if (longEnough && matches) { change.reset(); setDone(false); setAsking(true); } }}>
        <p className="rounded-lg border border-border p-4 text-base text-muted">{t("password.notice")}</p>
        {done && <p role="status" className="text-base">{t("password.changed")}</p>}
        <PasswordField label={t("reset.newPassword")} name="newPassword" autoComplete="new-password" autoFocus
                       value={password} onChange={(e) => setPassword(e.target.value)}>
          <p className={`flex items-center gap-1.5 text-sm ${longEnough ? "text-text" : "text-dim"}`}>
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="m5 12 5 5L20 7" />
            </svg>
            {t("password.ruleLength", { n: MIN_PASSWORD_LENGTH })}
          </p>
        </PasswordField>
        <PasswordField label={t("password.confirm")} name="confirmPassword" autoComplete="new-password"
                       value={again} onChange={(e) => setAgain(e.target.value)}>
          {again && !matches && <p className="text-sm text-danger">{t("password.mismatch")}</p>}
        </PasswordField>
        <Button type="submit" block disabled={!longEnough || !matches}>{t("password.submitButton")}</Button>
      </form>

      {asking && (
        <Dialog title={mfaOn ? t("mfa.code") : t("password.current")} onClose={() => setAsking(false)}>
          <form className="flex flex-col gap-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  const form = new FormData(e.currentTarget);
                  send(mfaOn ? { mfaCode: String(form.get("code") ?? "") } : { currentPassword: String(form.get("currentPassword") ?? "") });
                }}>
            {mfaOn
              ? <CodeInput name="code" label={t("mfa.code")} autoFocus disabled={change.isPending} onComplete={(code) => send({ mfaCode: code })} />
              : <PasswordField label={t("password.current")} name="currentPassword" autoComplete="current-password" autoFocus required />}
            <FormError error={change.error} />
            <Button type="submit" block pending={change.isPending}>{t("password.submitButton")}</Button>
          </form>
        </Dialog>
      )}
    </div>
  );
}
