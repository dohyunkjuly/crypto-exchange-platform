"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { ApiKeyCard } from "@/features/my/account/ApiKeyCard";
import { Requirements, useApiKeyBlockers, useNow } from "@/features/my/account/ApiKeys";
import { keyStatus } from "@/features/my/account/apiKeyMath";
import { CreatedKey, CreateForm } from "@/features/my/account/CreateApiKey";
import type { CreatedApiKey } from "@/features/my/shared/api";
import { useLocale, useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { localePath } from "@/lib/i18n/locales";
import { useRouter } from "next/navigation";
import { BackLink } from "@/features/my/shared/BackLink";
import { createLeadApiKey, fetchLeadApiKeys, revokeLeadApiKey } from "./api";

/**
 * The lead account's API keys. Made and deleted by its owner, behind the owner's authenticator
 * code, since a lead account never signs in; never withdrawing, and never edited -- a key that
 * needs other rights is deleted and made again, as for a sub-account.
 */
export function LeadKeys() {
  const t = useT();
  const describe = useDescribe();
  const now = useNow();
  const blockers = useApiKeyBlockers(now);
  const queryClient = useQueryClient();
  const keys = useQuery({ queryKey: ["copy-lead", "api-keys"], queryFn: ({ signal }) => fetchLeadApiKeys(signal) });
  const [confirming, setConfirming] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["copy-lead", "api-keys"] });
  const revoke = useMutation({ mutationFn: revokeLeadApiKey, onSettled: () => { setConfirming(null); refresh(); } });
  // Deleting is the server's revoke: the row stays for the audit trail and simply leaves this list.
  const listed = (keys.data ?? []).filter((k) => keyStatus(k, now) !== "revoked");

  return (
    <section className="flex flex-col gap-3">
      {blockers && blockers.length > 0 && <Requirements blockers={blockers} />}
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-base font-semibold">{t("menu.api")}</h2>
        {/* Its own page, as the account's keys have: a form that unfolds above a list is neither. */}
        {blockers && blockers.length === 0 && (
          <LocaleLink href="/copy/lead/keys/create" className="inline-flex h-10 items-center rounded-lg border border-border px-4 text-base font-medium hover:border-border-strong">
            + {t("apiKeys.create")}
          </LocaleLink>
        )}
      </div>
      <FormError error={revoke.error} />
      {confirming !== null && (
        <ConfirmDialog title={t("apiKeys.deleteTitle")} message={t("apiKeys.deleteConfirm")} pending={revoke.isPending}
                       onConfirm={() => revoke.mutate(confirming)} onClose={() => setConfirming(null)} />
      )}
      {keys.error ? <p role="alert" className="py-8 text-center text-base text-muted">{describe(keys.error)}</p>
        : keys.isPending ? <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>
          : listed.length === 0 ? <Empty className="rounded-lg border border-border">{t("apiKeys.empty")}</Empty>
            : listed.map((k) => <ApiKeyCard key={k.id} apiKey={k} now={now} editable={false} onDelete={() => setConfirming(k.id)} />)}
    </section>
  );
}

/** Making a key for the lead account on its own page, as the account's keys are made: the form, the code, the secret once. */
export function LeadKeyCreate() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const blockers = useApiKeyBlockers(useNow());
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const back = () => router.replace(localePath(locale, "/copy/lead"));
  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <BackLink href="/copy/lead" label={t("copy.leadAccount")} />
      <h1 className="text-xl font-semibold">{t("apiKeys.create")}</h1>
      {blockers === undefined ? <p className="text-base text-dim" aria-busy>{t("common.loading")}</p>
        : blockers.length > 0 ? <Requirements blockers={blockers} />
          : <CreateForm withdrawable={false} create={createLeadApiKey} onCancel={back}
                        onCreated={(key) => { setCreated(key); queryClient.invalidateQueries({ queryKey: ["copy-lead", "api-keys"] }); }} />}
      {/* The secret is shown this once, whichever way the modal goes. */}
      {created && <CreatedKey created={created} onDone={back} />}
    </div>
  );
}
