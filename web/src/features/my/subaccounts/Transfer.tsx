"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useSessionQuery } from "@/features/auth/session";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";
import { price } from "@/lib/format";
import { fetchAssets, fetchBalances, fetchSubAccountBalances, fetchSubAccounts, transfer } from "../shared/api";
import { BackLink } from "../shared/BackLink";
import { SelectFilter } from "../shared/filters";

const control = "h-10 rounded-lg border border-border bg-surface-raised px-3 text-base text-text outline-none focus:border-border-strong";

/**
 * Money between the master and its subs, or sub to sub. The amount is checked against the
 * sender's available balance here, so "Max" is exact and a short balance never reaches the
 * server. The transfer id is made once per form, so a retry after a timeout cannot double it.
 */
export function Transfer() {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();
  const me = useSessionQuery().data;
  const subs = useQuery({ queryKey: ["sub-accounts"], queryFn: ({ signal }) => fetchSubAccounts(signal) });
  const assets = useQuery({ queryKey: ["assets"], queryFn: ({ signal }) => fetchAssets(signal), staleTime: 5 * 60_000 });

  const masterId = me?.id ?? 0;
  // `?to=` and `?from=` are the asset screen's two transfer links; either side may be preset.
  const presetTo = Number(params.get("to") ?? "");
  const presetFrom = Number(params.get("from") ?? "");
  const [from, setFrom] = useState<string>(String(presetFrom || masterId));
  const [to, setTo] = useState<string>(presetTo ? String(presetTo) : "");
  // Empty until the balances are in: the asset is whatever the sender actually holds.
  const [asset, setAsset] = useState("");
  const [amount, setAmount] = useState("");
  const [transferId] = useState(() => crypto.randomUUID());
  const [done, setDone] = useState(false);

  const fromId = Number(from) || masterId;
  const balances = useQuery({
    queryKey: ["balances", "of", fromId],
    queryFn: ({ signal }) => (fromId === masterId ? fetchBalances(signal) : fetchSubAccountBalances(fromId, signal)),
    enabled: masterId > 0,
  });
  // Only what the sender has. A select of every listed asset is a list of things the transfer
  // would refuse, and "Max: 0" beside them.
  const holdings = (balances.data ?? []).filter((b) => Number(b.available) > 0);
  const held = holdings.some((b) => b.asset === asset) ? asset : (holdings[0]?.asset ?? "");
  const available = holdings.find((b) => b.asset === held)?.available ?? "0";
  const decimals = assets.data?.find((a) => a.symbol === held)?.displayDecimals ?? 8;

  const accounts = useMemo(() => {
    const list: { value: string; label: string }[] = [{ value: String(masterId), label: t("subAccounts.master") }];
    for (const s of subs.data ?? []) if (!s.frozen) list.push({ value: String(s.id), label: s.label });
    return list;
  }, [subs.data, masterId, t]);
  // The first account that is not the sender, so the form never opens on "master to master".
  const toId = to && to !== from ? to : (accounts.find((a) => a.value !== from)?.value ?? "");

  const send = useMutation({
    mutationFn: transfer,
    onSuccess: () => {
      setDone(true);
      queryClient.invalidateQueries({ queryKey: ["balances"] });
      queryClient.invalidateQueries({ queryKey: ["transfers"] });
    },
  });
  const tooMuch = amount !== "" && Number(amount) > Number(available);

  if (!me || subs.isPending) return <p className="text-base text-dim" aria-busy>{t("common.loading")}</p>;

  return (
    <div className="flex flex-col gap-6">
      <BackLink href="/my/sub-accounts" label={t("subAccounts.back")} />
      <h1 className="text-xl font-semibold">{t("subAccounts.transferTitle")}</h1>
      {done ? (
        <section className="flex max-w-lg flex-col gap-4 rounded-lg border border-border p-5">
          <p className="text-base">{t("subAccounts.transferDone")}</p>
          <Button className="self-start" onClick={() => router.replace(localePath(locale, "/my/sub-accounts"))}>{t("subAccounts.back")}</Button>
        </section>
      ) : (
        <form className="flex max-w-lg flex-col gap-5 rounded-lg border border-border p-5"
              onSubmit={(e) => {
                e.preventDefault();
                send.mutate({ fromUserId: fromId, toUserId: Number(toId), asset: held, amount, transferId });
              }}>
          <div className="grid gap-4 sm:grid-cols-2">
            <SelectFilter label={t("subAccounts.from")} value={from} onChange={(v) => { setFrom(v); if (v === to) setTo(""); }} options={accounts} />
            <SelectFilter label={t("subAccounts.to")} value={toId} onChange={setTo} options={accounts.filter((a) => a.value !== from)} />
          </div>
          {/* A sender that holds nothing still gets both fields, greyed: the form is what a
              transfer takes, and hiding them makes an empty account look like a broken page. */}
          <SelectFilter label={t("subAccounts.asset")} value={held} onChange={setAsset} disabled={holdings.length === 0}
                        options={holdings.length === 0 ? [{ value: "", label: "\u2014" }]
                                                       : holdings.map((b) => ({ value: b.asset, label: b.asset }))} />
          <label className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-muted">{t("subAccounts.amount")}</span>
            <span className="flex gap-2">
              <input className={`${control} num flex-1 disabled:cursor-not-allowed disabled:text-dim`} inputMode="decimal"
                     pattern="\d*\.?\d*" value={amount} required disabled={holdings.length === 0}
                     onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))} />
              <Button type="button" variant="secondary" disabled={holdings.length === 0} onClick={() => setAmount(available)}>{t("subAccounts.max")}</Button>
            </span>
            <span className={`num text-sm ${tooMuch ? "text-danger" : "text-dim"}`}>
              {holdings.length === 0 ? (balances.isPending ? t("common.loading") : t("subAccounts.nothingToTransfer"))
                : t("subAccounts.available", { amount: `${price(available, decimals)} ${held}` })}
            </span>
          </label>
          <FormError error={send.error} />
          <div className="flex gap-2">
            <Button type="submit" pending={send.isPending} disabled={!toId || !held || tooMuch || Number(amount) <= 0}>{t("subAccounts.transfer")}</Button>
            <Button type="button" variant="ghost" onClick={() => router.replace(localePath(locale, "/my/sub-accounts"))}>{t("common.cancel")}</Button>
          </div>
        </form>
      )}
    </div>
  );
}
