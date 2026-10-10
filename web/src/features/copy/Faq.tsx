"use client";

import { Faq as Questions } from "@/components/ui/Faq";
import { useT } from "@/lib/i18n";
import type { Terms } from "./api";

const QUESTIONS = ["what", "sizing", "failures", "lead", "share", "figures"] as const;

/**
 * The one explainer the product has besides the home page's, and it lives here because this
 * is the page a customer reads before putting money behind someone else's trades. The numbers
 * in it are the terms staff set, never typed into the text.
 */
export function Faq({ terms }: { terms: Terms }) {
  const t = useT();
  const vars = {
    asset: terms.quoteAsset, minRatio: terms.minFixedRatioAmount, minAmount: terms.minFixedAmount,
    leadMin: terms.leadMinBalance, failures: String(terms.failureLimit), percent: String(terms.profitShareBps / 100),
  };
  return <Questions title={t("copy.faq")} items={QUESTIONS.map((q) => ({ q: t(`copyFaq.${q}.q`), a: t(`copyFaq.${q}.a`, vars) }))} />;
}
