"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { CodeInput } from "@/components/ui/CodeInput";
import { FormError } from "@/components/ui/FormError";
import { resendVerification, verifyEmail } from "./api";
import { SESSION_KEY } from "./session";
import { BffCode, BffError } from "@/lib/bff/errors";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";

/** Behind the session gate: typing the code into a logged-in session is what proves the same
 *  person holds both the account and the mailbox. */
export function VerifyEmailForm() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const submit = useMutation({
    mutationFn: (code: string) => verifyEmail(code),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: SESSION_KEY }); router.replace(localePath(locale, "/my/dashboard")); },
  });
  const resend = useMutation({ mutationFn: resendVerification });
  const tooSoon = resend.error instanceof BffError && resend.error.code === BffCode.CODE_REQUESTED_TOO_SOON;

  return (
    <div className="mx-auto flex w-full max-w-[400px] flex-col gap-6 rounded-lg border border-border bg-surface p-6 sm:p-8">
      <div>
        <h1 className="text-xl font-semibold">{t("verify.title")}</h1>
      </div>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => { e.preventDefault(); submit.mutate(String(new FormData(e.currentTarget).get("code")).trim()); }}
      >
        <CodeInput name="code" autoFocus onComplete={(code) => submit.mutate(code)} />
        <FormError error={submit.error} />
        <Button type="submit" block pending={submit.isPending}>{t("verify.submit")}</Button>
      </form>
      <div className="flex items-center justify-between text-xs">
        <button type="button" onClick={() => resend.mutate()} disabled={resend.isPending}
                className="text-muted hover:text-text disabled:opacity-40">
          {t("verify.resend")}
        </button>
        {resend.isSuccess && <span className="text-dim">{t("verify.sent")}</span>}
        {tooSoon && <span role="alert" className="text-dim">{t("verify.tooSoon")}</span>}
      </div>
      {!tooSoon && <FormError error={resend.error} />}
    </div>
  );
}
