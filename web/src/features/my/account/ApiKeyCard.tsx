"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { CodeInput } from "@/components/ui/CodeInput";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { updateApiKey, type ApiKey } from "../shared/api";
import { expiresAtFor, keyStatus, maskKey, parseAllowlist, type Expiry } from "./apiKeyMath";
import { CopyButton } from "../shared/CopyButton";
import { Check, control } from "./CreateApiKey";
import { SelectFilter } from "../shared/filters";

/**
 * One key: the label, the masked key and what it may do at a glance; everything else behind
 * "Show details"; edit in place, behind the authenticator code, like creating one. Delete is an
 * icon: a row of "Delete" links reads like a list of things to click, and this is the one
 * button here that must not be clicked by accident.
 */
export function ApiKeyCard({ apiKey: k, now, onDelete, editable = true }: {
  apiKey: ApiKey; now: Date; onDelete: () => void; editable?: boolean;
}) {
  const t = useT();
  const [editing, setEditing] = useState(false);
  const live = keyStatus(k, now) === "active";

  return (
    <article className={`flex flex-col gap-3 rounded-lg border border-border p-4 ${live ? "" : "text-dim"}`} aria-label={k.label}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-base font-semibold">{k.label}</h3>
            <span className="rounded-full border border-border px-2 py-0.5 text-xs text-muted">
              {t(live ? "apiKeys.active" : "apiKeys.expired")}
            </span>
          </div>
          <span className="flex items-center gap-2 text-sm"><code className="num">{maskKey(k.apiKey)}</code>{live && <CopyButton text={k.apiKey} />}</span>
        </div>
        <div className="flex items-center gap-1">
          {live && editable && (
            <button type="button" onClick={() => setEditing((e) => !e)} aria-label={t("apiKeys.edit")} aria-expanded={editing}
                    title={t("apiKeys.edit")} className="rounded-md p-2 text-muted hover:bg-surface-raised hover:text-text">
              <PencilIcon />
            </button>
          )}
          <button type="button" onClick={onDelete} aria-label={t("apiKeys.delete")} title={t("apiKeys.delete")}
                  className="rounded-md p-2 text-muted hover:bg-surface-raised hover:text-danger">
            <TrashIcon />
          </button>
        </div>
      </div>

      {editing && <EditForm apiKey={k} onDone={() => setEditing(false)} />}
    </article>
  );
}

/** The create form's fields, filled from the key. The expiry is set afresh from the choice,
 *  counted from now, with the current one shown beside the field so nobody shortens or extends
 *  a key without seeing it. */
function EditForm({ apiKey: k, onDone }: { apiKey: ApiKey; onDone: () => void }) {
  const t = useT();
  const locale = useLocale();
  const queryClient = useQueryClient();
  const [label, setLabel] = useState(k.label);
  const [trade, setTrade] = useState(k.canTrade);
  const [withdraw, setWithdraw] = useState(k.canWithdraw);
  const [ips, setIps] = useState(k.ipAllowlist.join("\n"));
  const [expiry, setExpiry] = useState<Expiry>(k.expiresAt ? "90" : "never");
  const [asking, setAsking] = useState(false);
  const allowlist = parseAllowlist(ips);
  const canWithdraw = withdraw && allowlist.length > 0;
  const update = useMutation({
    mutationFn: updateApiKey,
    onSuccess: () => { setAsking(false); queryClient.invalidateQueries({ queryKey: ["api-keys"] }); onDone(); },
  });
  const submit = (mfaCode: string) => update.mutate({
    id: k.id, label: label.trim(), canTrade: trade, canWithdraw, ipAllowlist: allowlist,
    expiresAt: expiresAtFor(expiry, new Date()), mfaCode,
  });
  const current = k.expiresAt
    ? new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(k.expiresAt))
    : t("apiKeys.never");

  return (
    <>
    <form className="flex flex-col gap-4 border-t border-border pt-4" aria-label={t("apiKeys.editTitle")}
          onSubmit={(e) => { e.preventDefault(); update.reset(); setAsking(true); }}>
      <Field label={t("apiKeys.label")} name="label" maxLength={64} required value={label} onChange={(e) => setLabel(e.target.value)} />
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium text-muted">{t("apiKeys.permissions")}</legend>
        <Check label={t("apiKeys.read")} checked disabled />
        <Check label={t("apiKeys.trade")} checked={trade} onChange={setTrade} />
        <Check label={t("apiKeys.withdraw")} checked={canWithdraw} onChange={setWithdraw} disabled={allowlist.length === 0}
               hint={allowlist.length === 0 ? t("apiKeys.withdrawNeedsIps") : undefined} />
      </fieldset>
      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted">{t("apiKeys.ips")}</span>
        <textarea value={ips} onChange={(e) => setIps(e.target.value)} rows={3} spellCheck={false} className={`${control} num`} />
        <span className="text-sm text-dim">{t("apiKeys.ipsHint")}</span>
      </label>
      <div className="flex flex-col gap-1.5">
        <SelectFilter<Expiry> label={t("apiKeys.expiry")} value={expiry} onChange={setExpiry} options={[
          { value: "30", label: t("apiKeys.d30") }, { value: "90", label: t("apiKeys.d90") },
          { value: "365", label: t("apiKeys.d365") }, { value: "never", label: t("apiKeys.never") },
        ]} />
        <span className="text-sm text-dim">{t("apiKeys.currently", { value: current })}</span>
      </div>
      <div className="flex gap-2">
        <Button type="submit" pending={update.isPending}>{t("apiKeys.save")}</Button>
        <Button type="button" variant="ghost" onClick={onDone}>{t("common.cancel")}</Button>
      </div>
    </form>
    {asking && (
      <Dialog title={t("mfa.code")} onClose={() => setAsking(false)}>
        <form className="flex flex-col gap-4"
              onSubmit={(e) => { e.preventDefault(); submit(String(new FormData(e.currentTarget).get("code") ?? "")); }}>
          <CodeInput name="code" label={t("mfa.code")} autoFocus disabled={update.isPending} onComplete={submit} />
          <FormError error={update.error} />
          <Button type="submit" block pending={update.isPending}>{t("apiKeys.save")}</Button>
        </form>
      </Dialog>
    )}
    </>
  );
}

function TrashIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6" />
    </svg>
  );
}

function PencilIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </svg>
  );
}
