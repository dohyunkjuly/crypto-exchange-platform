"use client";

import { useT } from "@/lib/i18n";
import { Orders } from "../orders/Orders";
import { SubAccountPicker } from "./SubAccountPicker";

/** The orders page, for one sub: its open orders to cancel, its history, its trades. */
export function SubOrders() {
  const t = useT();
  return (
    <SubAccountPicker title={t("menu.subOrders")}>
      {(sub) => <Orders key={sub?.id ?? "none"} sub={sub ? sub.id : null} />}
    </SubAccountPicker>
  );
}
