"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { fetchApiKeys, fetchKyc, fetchMfa, revokeApiKey } from "../shared/api";
import { ApiKeyCard } from "./ApiKeyCard";
import { apiKeyBlockers, keyStatus, type Blocker } from "./apiKeyMath";

/** Now, fixed for the component's life so render stays pure. */
export function useNow(): Date {
  const [now] = useState(() => new Date());
  return now;
}

/** What still stands between this user and a key; empty once there is nothing. Undefined while loading. */
export function useApiKeyBlockers(now: Date): Blocker[] | undefined {
  const kyc = useQuery({ queryKey: ["kyc"], queryFn: ({ signal }) => fetchKyc(signal) });
  const mfa = useQuery({ queryKey: ["mfa"], queryFn: ({ signal }) => fetchMfa(signal) });
  return kyc.data && mfa.data ? apiKeyBlockers(kyc.data, mfa.data, now) : undefined;
}

export function ApiKeys() {
  const t = useT();
  const now = useNow();
  const blockers = useApiKeyBlockers(now);
  const ready = blockers !== undefined && blockers.length === 0;

  return (
    <div className="flex flex-col gap-6">
      {/* What stands in the way, if anything, at the very top: one bar, one link. */}
      {blockers && blockers.length > 0 && <Requirements blockers={blockers} />}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{t("apiKeys.title")}</h1>
        {/* Its own page, like Manage for the password and the authenticator. */}
        {ready
          ? <LocaleLink href="/my/account/api/create" className="inline-flex h-10 items-center rounded-lg bg-text px-4 text-base font-medium text-bg hover:opacity-85">{t("apiKeys.create")}</LocaleLink>
          : <Button disabled>{t("apiKeys.create")}</Button>}
      </div>
      <ApiKeyRules keys={["rule1", "rule2", "rule3", "rule4", "rule5", "rule6"]} />
      <KeyTable now={now} />
    </div>
  );
}

/** What a key can do and what it costs to hold one, stated where someone is about to make one.
 *  The sub-account screen has its own set: its keys are minted by a master, not by their owner. */
export function ApiKeyRules({ keys }: { keys: string[] }) {
  const t = useT();
  return (
    <ol className="list-decimal space-y-1.5 pl-5 text-sm text-muted">
      {keys.map((k) => <li key={k}>{t(`apiKeys.${k}` as "apiKeys.rule1")}</li>)}
    </ol>
  );
}

/** The first thing in the way, as one alert bar with the link that clears it. KYC before 2FA,
 *  since that is the order a person works through them. */
export function Requirements({ blockers }: { blockers: Blocker[] }) {
  const t = useT();
  const kyc = blockers.includes("kyc");
  return (
    <p role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border-strong bg-surface px-4 py-3 text-base">
      <span>{t(kyc ? "apiKeys.needKyc" : "apiKeys.needMfa")}</span>
      <LocaleLink href={kyc ? "/my/account/identification" : "/my/account/security"} className="font-medium underline underline-offset-4">
        {t(kyc ? "apiKeys.verify" : "apiKeys.turnOn")}
      </LocaleLink>
    </p>
  );
}

function KeyTable({ now }: { now: Date }) {
  const t = useT();
  const describe = useDescribe();
  const queryClient = useQueryClient();
  const keys = useQuery({ queryKey: ["api-keys"], queryFn: ({ signal }) => fetchApiKeys(signal) });
  const [confirming, setConfirming] = useState<string | null>(null);
  const revoke = useMutation({
    mutationFn: revokeApiKey,
    onSettled: () => { setConfirming(null); queryClient.invalidateQueries({ queryKey: ["api-keys"] }); },
  });

  if (keys.error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(keys.error)}</p>;
  if (keys.isPending) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;
  // Deleting is the server's revoke: the row stays for the audit trail and simply leaves this list.
  const listed = keys.data.filter((k) => keyStatus(k, now) !== "revoked");

  return (
    <div className="flex flex-col gap-3">
      <FormError error={revoke.error} />
      {confirming !== null && (
        <ConfirmDialog title={t("apiKeys.deleteTitle")} message={t("apiKeys.deleteConfirm")} pending={revoke.isPending}
                       onConfirm={() => revoke.mutate(confirming)} onClose={() => setConfirming(null)} />
      )}
      {listed.map((k) => <ApiKeyCard key={k.id} apiKey={k} now={now} onDelete={() => setConfirming(k.id)} />)}
      {listed.length === 0 && <Empty>{t("apiKeys.empty")}</Empty>}
    </div>
  );
}
