"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import type { MarketSymbol } from "@/features/markets/api";
import { useT, useTOptional } from "@/lib/i18n";
import { amendOrder, openOrdersKey, type Order } from "../shared/api";
import { amendableFields, draftOf, isQuoteSized, toAmendRequest, validateAmend } from "./amendMath";

/**
 * Edits an open order. Shows only the fields this order can change (amendMath), and sends only
 * the ones that did. The PATCH answers 202 before the engine decides, so success just marks the
 * row pending and closes; the socket's orderUpdate frame brings the new terms or the refusal.
 */
export function AmendOrder({ order, market, account, onClose }: { order: Order; market: MarketSymbol; account?: "lead"; onClose: () => void }) {
  const t = useT();
  const tOpt = useTOptional();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState(() => draftOf(order));
  const fields = amendableFields(order);
  const problems = validateAmend(order, draft, market);
  const problemText = problems[0]
    ? tOpt(`orders.amendProblem.${problems[0]}`) ?? tOpt(`tradeForm.problem.${problems[0]}`, { min: market.minQty, max: market.maxQty, notional: market.minNotional, asset: market.quoteAsset })
    : null;
  const amend = useMutation({
    mutationFn: (body: Parameters<typeof amendOrder>[0]) => amendOrder({ ...body, account }),
    // Never retried (AGENTS.md): a timeout says nothing about whether the engine has it.
    retry: false,
    onSuccess: () => {
      queryClient.setQueryData<Order[]>(openOrdersKey(account), (list) =>
        list?.map((o) => (o.orderId === order.orderId ? { ...o, amendPending: true, amendRejectReason: null } : o)));
      // Nothing is announced here or on failure: the dialog keeps the error, and the
      // engine's frame brings the outcome.
      onClose();
    },
  });
  const set = (key: keyof typeof draft) => (e: React.ChangeEvent<HTMLInputElement>) => setDraft((d) => ({ ...d, [key]: e.target.value }));
  const sizeAsset = isQuoteSized(order) ? market.quoteAsset : market.baseAsset;

  return (
    <Dialog title={t("orders.amendTitle", { symbol: order.symbol })} onClose={onClose}>
      <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); if (!problems.length) amend.mutate({ orderId: order.orderId, ...toAmendRequest(order, draft, market) }); }}>
        {fields.includes("stopPrice") && <Field label={t("orders.trigger")} inputMode="decimal" value={draft.stopPrice} onChange={set("stopPrice")} />}
        {fields.includes("price") && <Field label={t("orders.price")} inputMode="decimal" value={draft.price} onChange={set("price")} />}
        <Field label={`${t("orders.amount")} (${sizeAsset})`} inputMode="decimal" value={draft.size} onChange={set("size")}
               hint={order.trailingDeltaBps != null ? t("orders.amendTrailingHint") : t("orders.amendSizeHint")} />
        {problemText && <p className="text-sm text-muted">{problemText}</p>}
        <FormError error={amend.error} />
        <div className="mt-3 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose} disabled={amend.isPending}>{t("common.cancel")}</Button>
          <Button type="submit" pending={amend.isPending} disabled={problems.length > 0}>{t("orders.amendSubmit")}</Button>
        </div>
      </form>
    </Dialog>
  );
}
