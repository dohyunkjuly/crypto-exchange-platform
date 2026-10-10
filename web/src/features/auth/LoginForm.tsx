"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useCallback, useState } from "react";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";
import { EXCHANGE_NAME } from "@/lib/env";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { completeMfaLogin, login, type LoginOutcome } from "./api";
import { SESSION_KEY } from "./session";
import { currentNextPath } from "./nextPath";
import { MfaStep } from "./MfaStep";
import { GoogleSignInButton } from "./GoogleSignInButton";

/**
 * Password, then a code if the account has a second factor. One component for both phases:
 * the challenge token lives only in this state, and a route change would lose it.
 */
export function LoginForm() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [challengeToken, setChallengeToken] = useState<string | null>(null);

  /** Every way in ends here: the cookie is set, so the cached session is now wrong. */
  const signedIn = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: SESSION_KEY });
    router.replace(localePath(locale, currentNextPath()));
  }, [queryClient, router, locale]);

  // Password and Google produce the same outcome shape, so one handler serves both.
  const onOutcome = useCallback(
    (outcome: LoginOutcome) =>
      outcome.mfaRequired ? setChallengeToken(outcome.challengeToken) : signedIn(),
    [signedIn],
  );

  const passwordStep = useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password),
    onSuccess: onOutcome,
  });
  const codeStep = useMutation({
    mutationFn: (code: string) => completeMfaLogin(challengeToken!, code),
    onSuccess: signedIn,
  });

  if (challengeToken) {
    return (
      <MfaStep
        pending={codeStep.isPending}
        error={codeStep.error}
        onSubmit={(code) => codeStep.mutate(code)}
        onRestart={() => { setChallengeToken(null); codeStep.reset(); }}
      />
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">{t("login.title")}</h1>
      </div>

      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          const d = new FormData(e.currentTarget);
          passwordStep.mutate({ email: String(d.get("email")), password: String(d.get("password")) });
        }}
      >
        <Field label={t("login.email")} name="email" type="email" autoComplete="email" required autoFocus placeholder={t("common.emailPlaceholder")} />
        <Field
          label={t("login.password")}
          name="password"
          type="password"
          autoComplete="current-password"
          required
          trailing={
            <LocaleLink href="/password-reset" className="text-xs text-muted hover:text-text">
              {t("login.forgot")}
            </LocaleLink>
          }
        />
        <FormError error={passwordStep.error} />
        <Button type="submit" block pending={passwordStep.isPending}>{t("login.submit")}</Button>
      </form>

      <GoogleSignInButton onOutcome={onOutcome} text="signin_with" />

      <p className="text-center text-sm text-muted">
        {t("login.new", { name: EXCHANGE_NAME })}{" "}
        <LocaleLink href="/register" className="text-text underline-offset-4 hover:underline">
          {t("login.create")}
        </LocaleLink>
      </p>
    </div>
  );
}
