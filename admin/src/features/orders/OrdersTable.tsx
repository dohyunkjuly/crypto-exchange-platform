import Link from "next/link";
import { Table } from "@/components/Table";
import { exact } from "@/lib/money";
import { when } from "@/lib/time";
import type { Order } from "./api";
import { OrderStatusPill } from "./OrderStatusPill";

/**
 * Orders as a list shows them. Without the customer column on a customer's own file, where every
 * row is theirs.
 */
export function OrdersTable({ items, empty, footer, customer = true }: {
  items: Order[]; empty: string; footer?: React.ReactNode; customer?: boolean;
}) {
  const head = ["Placed", "Customer", "Pair", "Side", "Type", "Price", "Amount", "Filled", "Status"];
  const keep = (_: unknown, i: number) => customer || i !== 1;
  return (
    <Table
      empty={empty}
      head={head.filter(keep)}
      right={customer ? [5, 6, 7] : [4, 5, 6]}
      footer={footer}
      rows={items.map((o) => ({
        key: o.id,
        cells: [
          <Link key="t" href={`/orders/${o.id}`} className="font-mono text-text hover:text-accent">{when(o.createdAt)}</Link>,
          <Link key="c" href={`/users/${o.userId}`} className="text-text hover:text-accent">{o.userEmail ?? `#${o.userId}`}</Link>,
          <span key="s" className="font-semibold">{o.symbol}</span>,
          <Side key="d" side={o.side} />,
          <span key="y" className="text-muted">{typeName(o.type)}</span>,
          <span key="p" className="font-mono">{o.price ? exact(o.price) : <span className="text-dim">Market</span>}</span>,
          <span key="q" className="font-mono">{size(o)}</span>,
          <span key="f" className="font-mono text-muted">{exact(o.quoteOrderQty && !o.quantity ? o.quoteFilled : o.filled)}</span>,
          <OrderStatusPill key="st" status={o.status} />,
        ].filter(keep),
      }))}
    />
  );
}

export function Side({ side }: { side: "BUY" | "SELL" }) {
  return <span className={`font-semibold ${side === "BUY" ? "text-success" : "text-danger"}`}>{side === "BUY" ? "Buy" : "Sell"}</span>;
}

/** An order sized in quote shows the quote amount it asked for, with the quote named. */
export function size(o: Order): string {
  if (o.quantity) return exact(o.quantity);
  return o.quoteOrderQty ? `${exact(o.quoteOrderQty)} ${o.quote}` : "—";
}

export function typeName(type: string): string {
  return type.charAt(0) + type.slice(1).toLowerCase().replaceAll("_", " ");
}
