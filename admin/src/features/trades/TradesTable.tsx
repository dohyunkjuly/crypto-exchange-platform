import Link from "next/link";
import { Table } from "@/components/Table";
import { exact } from "@/lib/money";
import { when } from "@/lib/time";
import { Side } from "../orders/OrdersTable";
import type { Party, Trade } from "./api";

/**
 * Trades as a list shows them. Across customers, both sides; on a customer's file, their side --
 * what they did, what they paid -- and who was on the other.
 *
 * @param of the customer whose file this is
 */
export function TradesTable({ items, empty, footer, of }: {
  items: Trade[]; empty: string; footer?: React.ReactNode; of?: number;
}) {
  if (of === undefined) {
    return (
      <Table
        empty={empty} footer={footer} right={[2, 3, 4]}
        head={["Time", "Pair", "Price", "Quantity", "Total", "Buyer", "Seller", "Maker"]}
        rows={items.map((t) => ({
          key: t.id,
          cells: [
            <span key="t" className="font-mono text-muted">{when(t.executedAt)}</span>,
            <span key="s" className="font-semibold">{t.symbol}</span>,
            <span key="p" className="font-mono">{exact(t.price)}</span>,
            <span key="q" className="font-mono">{exact(t.quantity)}</span>,
            <span key="v" className="font-mono">{exact(t.quoteQuantity)}</span>,
            <Who key="b" party={t.buyer} />,
            <Who key="se" party={t.seller} />,
            <span key="m" className="text-muted">{t.buyerIsMaker ? "Buyer" : "Seller"}</span>,
          ],
        }))}
      />
    );
  }
  return (
    <Table
      empty={empty} footer={footer} right={[3, 4, 5, 7]}
      head={["Time", "Pair", "Side", "Price", "Quantity", "Total", "Role", "Fee", "Counterparty", "Order"]}
      rows={items.map((t) => {
        const bought = t.buyer.userId === of;
        const mine = bought ? t.buyer : t.seller;
        return {
          key: t.id,
          cells: [
            <span key="t" className="font-mono text-muted">{when(t.executedAt)}</span>,
            <span key="s" className="font-semibold">{t.symbol}</span>,
            <Side key="d" side={bought ? "BUY" : "SELL"} />,
            <span key="p" className="font-mono">{exact(t.price)}</span>,
            <span key="q" className="font-mono">{exact(t.quantity)}</span>,
            <span key="v" className="font-mono">{exact(t.quoteQuantity)}</span>,
            <span key="r" className="text-muted">{bought === t.buyerIsMaker ? "Maker" : "Taker"}</span>,
            <span key="f" className="font-mono">{exact(mine.fee)} <span className="text-muted">{mine.feeAsset}</span></span>,
            <Who key="c" party={bought ? t.seller : t.buyer} />,
            <Link key="o" href={`/orders/${mine.orderId}`} className="font-mono text-muted hover:text-accent">{mine.orderId.slice(0, 8)}</Link>,
          ],
        };
      })}
    />
  );
}

function Who({ party }: { party: Party }) {
  return <Link href={`/users/${party.userId}`} className="text-text hover:text-accent">{party.email ?? `#${party.userId}`}</Link>;
}
