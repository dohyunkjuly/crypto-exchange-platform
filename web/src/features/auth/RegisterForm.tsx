"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useCallback, useState } from "react";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { MIN_PASSWORD_LENGTH, login, register, type LoginOutcome } from "./api";
import { SESSION_KEY } from "./session";
import { currentNextPath } from "./nextPath";
import { MfaStep } from "./MfaStep";
import { GoogleSignInButton } from "./GoogleSignInButton";
import { BffCode, BffError } from "@/lib/bff/errors";
import { completeMfaLogin } from "./api";

/** Registering by password does not sign you in, so it lands on sign-in with the address
 *  prefilled. Google creates the account AND signs in, hence the outcome handling here too. */
export function RegisterForm() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [tooShort, setTooShort] = useState(false);
  const [challengeToken, setChallengeToken] = useState<string | null>(null);

  const signedIn = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: SESSION_KEY });
    router.replace(localePath(locale, currentNextPath()));
  }, [queryClient, router, locale]);
  const onOutcome = useCallback(
    (o: LoginOutcome) => (o.mfaRequired ? setChallengeToken(o.challengeToken) : signedIn()),
    [signedIn],
  );
  const codeStep = useMutation({
    mutationFn: (code: string) => completeMfaLogin(challengeToken!, code),
    onSuccess: signedIn,
  });

  // Register, then sign straight in and land on verification. The account exists unverified
  // (the backend verifies inside a session, deliberately), so the funnel is what makes the
  // code unavoidable -- RequireSession keeps an unverified user on that page.
  const submit = useMutation({
    mutationFn: async (a: { email: string; password: string; name: string }) => {
      await register(a.email, a.password, a.name);
      return login(a.email, a.password);
    },
    onSuccess: (outcome: LoginOutcome) => {
      if (outcome.mfaRequired) { setChallengeToken(outcome.challengeToken); return; }
      queryClient.invalidateQueries({ queryKey: SESSION_KEY });
      router.replace(localePath(locale, "/verify-email"));
    },
  });
  const taken = submit.error instanceof BffError && submit.error.code === BffCode.EMAIL_ALREADY_REGISTERED;

  if (challengeToken) {
    return (
      <MfaStep
        pending={codeStep.isPending}
        error={codeStep.error}
        onSubmit={(c) => codeStep.mutate(c)}
        onRestart={() => { setChallengeToken(null); codeStep.reset(); }}
      />
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">{t("register.title")}</h1>
      </div>

      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          const d = new FormData(e.currentTarget);
          const password = String(d.get("password"));
          if (password.length < MIN_PASSWORD_LENGTH) { setTooShort(true); return; }
          setTooShort(false);
          submit.mutate({ email: String(d.get("email")), password, name: String(d.get("name") ?? "") });
        }}
      >
        <Field label={t("register.email")} name="email" type="email" autoComplete="email" required autoFocus placeholder={t("common.emailPlaceholder")} />
        <Field label={t("register.name")} name="name" autoComplete="name" placeholder={t("register.nameOptional")} />
        <Field
          label={t("register.password")}
          name="password"
          type="password"
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD_LENGTH}
          hint={t("register.minLength", { n: MIN_PASSWORD_LENGTH })}
          error={tooShort ? t("register.tooShort", { n: MIN_PASSWORD_LENGTH }) : null}
        />
        {taken ? (
          <p role="alert" className="text-sm text-muted">
            {t("register.taken")}{" "}
            <LocaleLink href="/login" className="text-text underline-offset-4 hover:underline">{t("register.signInInstead")}</LocaleLink>
          </p>
        ) : (
          <FormError error={submit.error} />
        )}
        <Button type="submit" block pending={submit.isPending}>{t("register.submit")}</Button>
      </form>

      <GoogleSignInButton onOutcome={onOutcome} text="signup_with" />

      <p className="text-center text-sm text-muted">
        {t("register.have")}{" "}
        <LocaleLink href="/login" className="text-text underline-offset-4 hover:underline">{t("register.signIn")}</LocaleLink>
      </p>
    </div>
  );
}
