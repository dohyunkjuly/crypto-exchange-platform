"use client";

import { Button } from "@/components/ui/Button";
import { useState } from "react";
import { Field } from "@/components/ui/Field";
import { CodeInput } from "@/components/ui/CodeInput";
import { FormError } from "@/components/ui/FormError";
import { BffCode, BffError } from "@/lib/bff/errors";
import { useT } from "@/lib/i18n";

/**
 * One input for both a TOTP code and a recovery code; the server tells them apart by shape.
 * `inputMode="numeric"` but not `type="number"` -- a recovery code is not numeric, and
 * `type="number"` strips a TOTP code's leading zeros.
 */
export function MfaStep({
  pending, error, onSubmit, onRestart,
}: {
  pending: boolean;
  error: unknown;
  onSubmit: (code: string) => void;
  onRestart: () => void;
}) {
  // A challenge is single-use and expires; another code cannot help, so say that instead.
  const t = useT();
  const expired = error instanceof BffError && error.code === BffCode.MFA_CHALLENGE_EXPIRED;
  // A TOTP code is six digits; a recovery code is not, so it gets a plain field on request.
  const [recovery, setRecovery] = useState(false);

  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(String(new FormData(e.currentTarget).get("code")).trim());
      }}
    >
      <div>
        <h1 className="text-xl font-semibold">{t("mfa.title")}</h1>
        <p className="mt-1 text-sm text-muted">
          Enter the code from your authenticator app, or a recovery code.
        </p>
      </div>

      {recovery ? (
        <Field label={t("mfa.recoveryCode")} name="code" autoComplete="off" autoFocus required disabled={expired} placeholder="XXXX-XXXX-XXXX-XXXX" />
      ) : (
        <CodeInput name="code" label={t("mfa.code")} autoFocus disabled={expired} onComplete={onSubmit} />
      )}
      {!expired && (
        <button type="button" onClick={() => setRecovery((r) => !r)} className="self-start text-sm text-muted hover:text-text">
          {recovery ? t("mfa.useApp") : t("mfa.useRecovery")}
        </button>
      )}

      {expired ? (
        <p role="alert" className="text-sm text-danger">{t("mfa.expired")}</p>
      ) : (
        <FormError error={error} />
      )}

      {expired ? (
        <Button type="button" block onClick={onRestart}>{t("mfa.back")}</Button>
      ) : (
        <div className="flex flex-col gap-3">
          <Button type="submit" block pending={pending}>{t("mfa.verify")}</Button>
          <Button type="button" variant="ghost" onClick={onRestart}>{t("mfa.different")}</Button>
        </div>
      )}
    </form>
  );
}
