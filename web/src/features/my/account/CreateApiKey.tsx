"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeInput } from "@/components/ui/CodeInput";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";
import { createApiKey, type CreatedApiKey, type NewApiKey } from "../shared/api";
import { expiresAtFor, parseAllowlist, type Expiry } from "./apiKeyMath";
import { Requirements, useApiKeyBlockers, useNow } from "./ApiKeys";
import { BackLink } from "../shared/BackLink";
import { CopyButton } from "../shared/CopyButton";
import { SelectFilter } from "../shared/filters";

/**
 * Creating a key on its own page, like Manage for the password and the authenticator: the form,
 * the authenticator code in a pop-up, then the secret, shown this once.
 */
export function CreateApiKey() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const blockers = useApiKeyBlockers(useNow());
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const back = () => router.replace(localePath(locale, "/my/account/api"));

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/my/account/api" label={t("menu.api")} />
      <h1 className="text-xl font-semibold">{t("apiKeys.create")}</h1>
      {blockers === undefined ? <p className="text-base text-dim" aria-busy>{t("common.loading")}</p>
        : blockers.length > 0 ? <Requirements blockers={blockers} />
          : <CreateForm onCreated={setCreated} onCancel={back} />}
      {/* A modal: the page behind it is done. Any way of closing it means "I've saved it", so
          the secret is shown this once whichever way the modal goes. */}
      {created && <CreatedKey created={created} onDone={back} />}
    </div>
  );
}

export const control = "rounded-lg border border-border bg-surface-raised px-3 py-2 text-base text-text outline-none focus:border-border-strong";

/** `create`: what mints the key. The master's own by default; a sub-account's goes to the
 *  sub's endpoint and, since a sub never withdraws, offers no withdraw box. */
export function CreateForm({ onCreated, onCancel, create: mint = createApiKey, withdrawable = true }: {
  onCreated: (key: CreatedApiKey) => void; onCancel: () => void;
  create?: (key: NewApiKey) => Promise<CreatedApiKey>; withdrawable?: boolean;
}) {
  const t = useT();
  const queryClient = useQueryClient();
  const [trade, setTrade] = useState(false);
  const [withdraw, setWithdraw] = useState(false);
  const [ips, setIps] = useState("");
  const [expiry, setExpiry] = useState<Expiry>("90");
  const [label, setLabel] = useState("");
  // The code is asked for last, in a pop-up, like the password change: the form is the what, this is the proof.
  const [asking, setAsking] = useState(false);
  const allowlist = parseAllowlist(ips);
  // The server refuses withdrawals without an allowlist (2005); the box follows the list.
  const canWithdraw = withdraw && allowlist.length > 0;
  const create = useMutation({
    mutationFn: mint,
    onSuccess: (key) => { setAsking(false); queryClient.invalidateQueries({ queryKey: ["api-keys"] }); onCreated(key); },
  });
  const submit = (mfaCode: string) => create.mutate({
    label: label.trim(), canRead: true, canTrade: trade, canWithdraw,
    ipAllowlist: allowlist, expiresAt: expiresAtFor(expiry, new Date()), mfaCode,
  });

  return (
    <>
    <form className="flex max-w-xl flex-col gap-5 rounded-lg border border-border p-5"
          onSubmit={(e) => { e.preventDefault(); create.reset(); setAsking(true); }}>
      <Field label={t("apiKeys.label")} name="label" maxLength={64} required autoFocus hint={t("apiKeys.labelHint")}
             value={label} onChange={(e) => setLabel(e.target.value)} />
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium text-muted">{t("apiKeys.permissions")}</legend>
        <Check label={t("apiKeys.read")} checked disabled />
        <Check label={t("apiKeys.trade")} checked={trade} onChange={setTrade} />
        {withdrawable && (
          <Check label={t("apiKeys.withdraw")} checked={canWithdraw} onChange={setWithdraw} disabled={allowlist.length === 0}
                 hint={allowlist.length === 0 ? t("apiKeys.withdrawNeedsIps") : undefined} />
        )}
      </fieldset>
      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("apiKeys.ips")}</span>
        <textarea value={ips} onChange={(e) => setIps(e.target.value)} rows={3} spellCheck={false} className={`${control} num`} />
        <span className="text-sm text-dim">{t("apiKeys.ipsHint")}</span>
      </label>
      <SelectFilter<Expiry> label={t("apiKeys.expiry")} value={expiry} onChange={setExpiry} options={[
        { value: "30", label: t("apiKeys.d30") }, { value: "90", label: t("apiKeys.d90") },
        { value: "365", label: t("apiKeys.d365") }, { value: "never", label: t("apiKeys.never") },
      ]} />
      <div className="flex gap-2">
        <Button type="submit" pending={create.isPending}>{t("apiKeys.create")}</Button>
        <Button type="button" variant="ghost" onClick={onCancel}>{t("common.cancel")}</Button>
      </div>
    </form>
    {asking && (
      <Dialog title={t("mfa.code")} onClose={() => setAsking(false)}>
        <form className="flex flex-col gap-4"
              onSubmit={(e) => { e.preventDefault(); submit(String(new FormData(e.currentTarget).get("code") ?? "")); }}>
          <CodeInput name="code" label={t("mfa.code")} autoFocus disabled={create.isPending} onComplete={submit} />
          <FormError error={create.error} />
          <Button type="submit" block pending={create.isPending}>{t("apiKeys.create")}</Button>
        </form>
      </Dialog>
    )}
    </>
  );
}

export function Check({ label, checked, onChange, disabled, hint }: {
  label: string; checked: boolean; onChange?: (v: boolean) => void; disabled?: boolean; hint?: string;
}) {
  return (
    <label className={`flex items-center gap-2 text-base ${disabled ? "text-dim" : "text-text"}`}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange?.(e.target.checked)} className="size-4 accent-current" />
      {label}
      {hint && <span className="text-sm text-dim">· {hint}</span>}
    </label>
  );
}

export function CreatedKey({ created, onDone }: { created: CreatedApiKey; onDone: () => void }) {
  const t = useT();
  return (
    <Dialog title={t("apiKeys.createdTitle")} onClose={onDone}>
      <div className="flex flex-col gap-4">
        <p role="alert" className="text-base">{t("apiKeys.secretOnce")}</p>
        {[[t("apiKeys.apiKey"), created.apiKey], [t("apiKeys.secretKey"), created.secret]].map(([label, value]) => (
          <div key={label} className="flex flex-col gap-1">
            <span className="text-sm text-muted">{label}</span>
            <div className="flex items-center gap-3"><code className="num break-all text-base">{value}</code><CopyButton text={value} /></div>
          </div>
        ))}
        <Button block onClick={onDone}>{t("apiKeys.savedSecret")}</Button>
      </div>
    </Dialog>
  );
}
