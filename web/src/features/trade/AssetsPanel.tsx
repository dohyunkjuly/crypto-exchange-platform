"use client";

import { useQuery } from "@tanstack/react-query";
import { CoinIcon } from "@/components/ui/CoinIcon";
import { useSession } from "@/features/auth/session";
import type { MarketSymbol } from "@/features/markets/api";
import { balancesKey, fetchBalances } from "@/features/my/shared/api";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { price as fmt } from "@/lib/format";
import { scaleOf } from "@/lib/money";

/** Under the form: this pair's two balances in the account being traded, available and in orders. Empty when signed out. */
export function AssetsPanel({ market: m, account }: { market: MarketSymbol; account?: "lead" }) {
  const t = useT();
  const session = useSession();
  const signedIn = session.status === "signedIn";
  const balances = useQuery({ queryKey: balancesKey(account), queryFn: ({ signal }) => fetchBalances(signal, account), enabled: signedIn });
  const row = (asset: string, digits: number) => {
    const b = balances.data?.find((x) => x.asset === asset);
    return (
      <div key={asset} className="flex items-center justify-between py-1.5 text-xs">
        <span className="flex items-center gap-2"><CoinIcon symbol={asset} size={16} />{asset}</span>
        <span className="num flex flex-col items-end"><span>{b ? fmt(b.available, digits) : "--"}</span><span className="text-dim">{t("trade.inOrders")} {b ? fmt(b.locked, digits) : "--"}</span></span>
      </div>
    );
  };
  return (
    <div className="flex flex-col gap-2 border-t border-border p-4">
      <div className="flex items-center justify-between text-sm font-semibold">{t("trade.assetsOf", { asset: m.quoteAsset })}<LocaleLink href={account ? "/copy/lead" : "/my/assets"} className="text-xs font-normal text-muted hover:text-text">{t("trade.viewMore")}</LocaleLink></div>
      {signedIn ? [row(m.quoteAsset, m.quoteDecimals), row(m.baseAsset, scaleOf(m.qtyStep))] : <p className="py-6 text-center text-xs text-dim">{t("trade.assetsSignedOut")}</p>}
    </div>
  );
}
