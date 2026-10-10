"use client";

import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { CodeInput } from "@/components/ui/CodeInput";
import { FormError } from "@/components/ui/FormError";
import { MIN_PASSWORD_LENGTH, confirmPasswordReset, requestPasswordReset } from "./api";
import { needsSecondFactor } from "./secondFactor";

/** Request a code, then use it. One component so the address carries into the confirm call.
 *  A code, never a link (AGENTS.md). */
export function PasswordResetForm() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);
  const request = useMutation({
    mutationFn: (address: string) => requestPasswordReset(address),
    onSuccess: (_r, address) => setEmail(address),
  });

  if (email) return <ConfirmStep email={email} onDone={() => router.replace(localePath(locale, "/login"))} />;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">{t("reset.title")}</h1>
      </div>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => { e.preventDefault(); request.mutate(String(new FormData(e.currentTarget).get("email"))); }}
      >
        <Field label={t("reset.email")} name="email" type="email" autoComplete="email" required autoFocus placeholder={t("common.emailPlaceholder")} />
        <FormError error={request.error} />
        <Button type="submit" block pending={request.isPending}>{t("reset.send")}</Button>
      </form>
      <p className="text-center text-sm text-muted">
        <LocaleLink href="/login" className="text-text underline-offset-4 hover:underline">{t("reset.back")}</LocaleLink>
      </p>
    </div>
  );
}

function ConfirmStep({ email, onDone }: { email: string; onDone: () => void }) {
  const t = useT();
  const [tooShort, setTooShort] = useState(false);
  const confirm = useMutation({
    mutationFn: (a: { code: string; newPassword: string; mfaCode: string }) => confirmPasswordReset({ email, ...a }),
    onSuccess: onDone,
  });
  // The account has 2FA and a reset must not be a way around it: the form grows a field.
  const needsMfa = needsSecondFactor(confirm.error, confirm.variables?.mfaCode);

  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault();
        const d = new FormData(e.currentTarget);
        const newPassword = String(d.get("newPassword"));
        if (newPassword.length < MIN_PASSWORD_LENGTH) { setTooShort(true); return; }
        setTooShort(false);
        confirm.mutate({ code: String(d.get("code")).trim(), newPassword, mfaCode: String(d.get("mfaCode") ?? "").trim() });
      }}
    >
      <div>
        <h1 className="text-xl font-semibold">{t("reset.codeTitle")}</h1>
        {/* Must not say whether the address exists; the request endpoint answers the same either way. */}
        <p className="mt-1 text-sm text-muted">{t("reset.sent", { email })}</p>
      </div>
      <div className="flex flex-col gap-4">
        <CodeInput name="code" autoFocus />
        <Field label={t("reset.newPassword")} name="newPassword" type="password" autoComplete="new-password" required minLength={MIN_PASSWORD_LENGTH}
               hint={t("register.minLength", { n: MIN_PASSWORD_LENGTH })} error={tooShort ? t("register.tooShort", { n: MIN_PASSWORD_LENGTH }) : null} />
        {needsMfa && (
          <Field label={t("reset.mfaCode")} name="mfaCode" inputMode="numeric" autoComplete="one-time-code" required autoFocus
                 hint={t("reset.mfaHint")} />
        )}
      </div>
      {needsMfa ? <p role="alert" className="text-sm text-muted">{t("reset.mfaNeeded")}</p> : <FormError error={confirm.error} />}
      <Button type="submit" block pending={confirm.isPending}>{t("reset.submit")}</Button>
    </form>
  );
}
