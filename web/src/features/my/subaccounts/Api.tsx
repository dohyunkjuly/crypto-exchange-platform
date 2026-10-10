"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormError, useDescribe } from "@/components/ui/FormError";
import { useT } from "@/lib/i18n";
import { createSubAccountApiKey, fetchAllSubAccountApiKeys, fetchSubAccounts, revokeSubAccountApiKey,
  type ApiKey, type CreatedApiKey, type SubAccount } from "../shared/api";
import { Pager, usePage } from "../shared/Pager";
import { SearchFilters } from "../shared/SearchFilters";
import { SelectFilter, TextFilter } from "../shared/filters";
import { ApiKeyRules, Requirements, useApiKeyBlockers, useNow } from "../account/ApiKeys";
import { keyStatus, maskKey } from "../account/apiKeyMath";
import { CreatedKey, CreateForm } from "../account/CreateApiKey";
import { SubSelect } from "./SubAccountFilters";

const PER_PAGE = 10;
type KeyFilter = { sub: string; label: string; apiKey: string };
const NO_FILTER: KeyFilter = { sub: "", label: "", apiKey: "" };
/** A key with the sub it belongs to, since one list holds every sub's. */
type Row = ApiKey & { sub: SubAccount };

/**
 * Every sub's keys in one list. Creating and revoking are the master's, behind the master's
 * authenticator code -- a sub never signs in, so it can neither mint nor revoke its own
 * (docs/reference/sub-accounts.md). There is no edit: a key that needs other rights is
 * revoked and made again.
 */
export function SubApi() {
  const t = useT();
  const describe = useDescribe();
  const now = useNow();
  const blockers = useApiKeyBlockers(now);
  const [applied, setApplied] = useState<KeyFilter>(NO_FILTER);
  const [draft, setDraft] = useState<KeyFilter>(NO_FILTER);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const [confirming, setConfirming] = useState<Row | null>(null);

  const queryClient = useQueryClient();
  const subs = useQuery({ queryKey: ["sub-accounts"], queryFn: ({ signal }) => fetchSubAccounts(signal) });
  const keys = useQuery({ queryKey: ["sub-accounts", "api-keys"], queryFn: ({ signal }) => fetchAllSubAccountApiKeys(signal) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["sub-accounts", "api-keys"] });
  const revoke = useMutation({ mutationFn: revokeSubAccountApiKey, onSettled: () => { setConfirming(null); refresh(); } });

  const all = subs.data ?? [];
  // Revoked rows stay on the server for the audit trail; they simply leave this list.
  const rows: Row[] = all.flatMap((sub) => (keys.data?.[String(sub.id)] ?? [])
    .filter((k) => keyStatus(k, now) !== "revoked")
    .map((k) => ({ ...k, sub })));
  const shown = rows.filter((r) => (!applied.sub || String(r.sub.id) === applied.sub)
    && (!applied.label || r.label.toLowerCase().includes(applied.label.trim().toLowerCase()))
    && (!applied.apiKey || r.apiKey.toLowerCase().includes(applied.apiKey.trim().toLowerCase())));
  const page = usePage(shown, PER_PAGE);
  const error = subs.error ?? keys.error;
  const ready = blockers !== undefined && blockers.length === 0;
  const open = all.filter((s) => !s.frozen);

  return (
    <div className="flex flex-col gap-6">
      {/* What stands in the way, if anything, at the very top: the master's own gate is the
          sub's, since the master is who creates the key. */}
      {blockers && blockers.length > 0 && <Requirements blockers={blockers} />}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{t("menu.subApi")}</h1>
        <Button disabled={!ready || open.length === 0 || creating} onClick={() => setCreating(true)}>
          + {t("subAccounts.createKey")}
        </Button>
      </div>

      <ApiKeyRules keys={["subRule1", "subRule2", "subRule3", "subRule4", "subRule5"]} />

      <SearchFilters onSearch={() => setApplied(draft)} onReset={() => { setDraft(NO_FILTER); setApplied(NO_FILTER); }}
                     actions={<span className="text-base text-muted">{t("subAccounts.keyCount", { n: rows.length })}</span>}>
        <SubSelect subs={all} value={draft.sub} onChange={(sub) => setDraft((d) => ({ ...d, sub }))} />
        <TextFilter label={t("apiKeys.label")} value={draft.label} onChange={(label) => setDraft((d) => ({ ...d, label }))} />
        <TextFilter label={t("apiKeys.apiKey")} value={draft.apiKey} onChange={(apiKey) => setDraft((d) => ({ ...d, apiKey }))} />
      </SearchFilters>

      {creating && <Create subs={open} onCancel={() => setCreating(false)}
                           onCreated={(key) => { setCreating(false); setCreated(key); refresh(); }} />}
      {created && <CreatedKey created={created} onDone={() => setCreated(null)} />}

      <FormError error={revoke.error} />
      {confirming && (
        <ConfirmDialog title={t("apiKeys.deleteTitle")} message={t("apiKeys.deleteConfirm")} pending={revoke.isPending}
                       onConfirm={() => revoke.mutate({ id: confirming.sub.id, keyId: confirming.id })}
                       onClose={() => setConfirming(null)} />
      )}

      {error ? <p role="alert" className="py-8 text-center text-base text-muted">{describe(error)}</p>
        : subs.isPending || keys.isPending ? <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>
        : <List rows={page.rows} empty={t(rows.length === 0 ? "subAccounts.noKeys" : "subAccounts.noKeyMatch")}
                onDelete={setConfirming} />}

      <Pager current={page.current} pages={page.pages} onPage={page.setPage} />
    </div>
  );
}

/** Which sub the key is for, then the same form the master's own keys use, minus withdrawals. */
function Create({ subs, onCreated, onCancel }: {
  subs: SubAccount[]; onCreated: (key: CreatedApiKey) => void; onCancel: () => void;
}) {
  const t = useT();
  const [sub, setSub] = useState(String(subs[0]?.id ?? ""));

  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border p-5">
      <SelectFilter label={t("subAccounts.pick")} value={sub} onChange={setSub}
                    options={subs.map((s) => ({ value: String(s.id), label: s.label }))} />
      {/* Remounted per sub, so a half-filled form never lands on the account next to it. */}
      <CreateForm key={sub} withdrawable={false} onCancel={onCancel} onCreated={onCreated}
                  create={(key) => createSubAccountApiKey({ id: Number(sub), ...key })} />
    </section>
  );
}

function List({ rows, empty, onDelete }: { rows: Row[]; empty: string; onDelete: (row: Row) => void }) {
  const t = useT();
  const ips = (k: ApiKey) => (k.ipAllowlist.length === 0 ? t("apiKeys.unrestricted") : t("apiKeys.ipCount", { n: k.ipAllowlist.length }));

  return (
    <div className="flex flex-col gap-3">
      {/* Cards on a phone: eight columns of a key row do not survive a narrow screen. */}
      <ul className="flex flex-col gap-3 sm:hidden">
        {rows.map((r) => (
          <li key={r.id} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <div className="flex items-center justify-between gap-3">
              <span className="text-base font-medium">{r.label}</span>
              <span className="flex items-center gap-2 text-sm text-muted">{r.sub.label}<Status sub={r.sub} /></span>
            </div>
            <code className="num break-all text-sm text-muted">{maskKey(r.apiKey)}</code>
            <span className="text-sm text-muted">{t("apiKeys.read")}: <Mark on={r.canRead} /> · {t("apiKeys.trade")}: <Mark on={r.canTrade} /></span>
            <span className="text-sm text-muted">{t("apiKeys.ipAccess")}: {ips(r)}</span>
            <div className="mt-1 flex border-t border-border pt-3 text-base">
              <button onClick={() => onDelete(r)} className="text-muted underline-offset-4 hover:text-danger hover:underline">{t("apiKeys.delete")}</button>
            </div>
          </li>
        ))}
      </ul>

      <div className="hidden overflow-x-auto rounded-lg border border-border sm:block">
        <table className="w-full whitespace-nowrap text-base">
          <thead><tr className="border-b border-border text-left text-sm text-muted">
            <th className="px-4 py-3 font-medium">{t("subAccounts.pick")}</th>
            <th className="px-4 py-3 font-medium">{t("subAccounts.status")}</th>
            <th className="px-4 py-3 font-medium">{t("apiKeys.label")}</th>
            <th className="px-4 py-3 font-medium">{t("apiKeys.apiKey")}</th>
            <th className="px-4 py-3 text-center font-medium">{t("apiKeys.read")}</th>
            <th className="px-4 py-3 text-center font-medium">{t("apiKeys.trade")}</th>
            <th className="px-4 py-3 font-medium">{t("apiKeys.ipAccess")}</th>
            <th className="px-4 py-3 text-right font-medium">{t("subAccounts.actions")}</th>
          </tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-b border-border transition-colors last:border-0 hover:bg-surface-raised">
                <td className="px-4 py-3 font-medium">{r.sub.label}</td>
                <td className="px-4 py-3"><Status sub={r.sub} /></td>
                <td className="px-4 py-3">{r.label}</td>
                <td className="px-4 py-3 text-muted"><code className="num">{maskKey(r.apiKey)}</code></td>
                <td className="px-4 py-3 text-center"><Mark on={r.canRead} /></td>
                <td className="px-4 py-3 text-center"><Mark on={r.canTrade} /></td>
                <td className="px-4 py-3 text-muted">{ips(r)}</td>
                <td className="px-4 py-3 text-right">
                  <button onClick={() => onDelete(r)} className="text-muted underline-offset-4 hover:text-danger hover:underline">{t("apiKeys.delete")}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length === 0 && <Empty className="rounded-lg border border-border sm:-mt-3 sm:rounded-t-none sm:border-t-0">{empty}</Empty>}
    </div>
  );
}

/** The owning sub's state. A frozen sub's keys still read and cancel but cannot trade
 *  (docs/reference/trading-gate.md), so a key that looks fine and refuses orders needs this
 *  column to explain itself. */
function Status({ sub }: { sub: SubAccount }) {
  const t = useT();
  return (
    <span className={sub.frozen ? "text-danger" : "text-up"}>
      {t(sub.frozen ? "subAccounts.frozen" : "subAccounts.active")}
    </span>
  );
}

/** A permission, named for a screen reader rather than left as a bare glyph. */
function Mark({ on }: { on: boolean }) {
  const t = useT();
  return (
    <span className={on ? "text-up" : "text-dim"} title={t(on ? "apiKeys.granted" : "apiKeys.notGranted")}>
      <span aria-hidden>{on ? "✓" : "—"}</span>
      <span className="sr-only">{t(on ? "apiKeys.granted" : "apiKeys.notGranted")}</span>
    </span>
  );
}
