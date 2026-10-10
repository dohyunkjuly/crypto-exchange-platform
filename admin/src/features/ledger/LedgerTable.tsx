import Link from "next/link";
import { Table } from "@/components/Table";
import { exact } from "@/lib/money";
import { when } from "@/lib/time";
import { TYPES, type Entry } from "./api";

/**
 * Ledger lines as a list shows them. Every digit, never rounded to the asset's display places:
 * the ledger is where a discrepancy is looked for, and rounding is where one would hide.
 */
export function LedgerTable({ items, empty, footer, customer = true }: {
  items: Entry[]; empty: string; footer?: React.ReactNode; customer?: boolean;
}) {
  const head = ["Time", "Customer", "Asset", "Movement", "Amount", "Balance after", "Cause"];
  const keep = (_: unknown, i: number) => customer || i !== 1;
  return (
    <Table
      empty={empty}
      footer={footer}
      head={head.filter(keep)}
      right={customer ? [4, 5] : [3, 4]}
      rows={items.map((e) => ({
        key: e.id,
        cells: [
          <span key="t" className="font-mono text-muted">{when(e.createdAt)}</span>,
          <Link key="c" href={`/users/${e.userId}`} className="text-text hover:text-accent">{e.userEmail ?? `#${e.userId}`}</Link>,
          <span key="a" className="font-semibold">{e.asset}</span>,
          <span key="y">{TYPES[e.type] ?? e.type}</span>,
          <span key="m" className={`font-mono ${e.amount.startsWith("-") ? "text-danger" : "text-success"}`}>
            {e.amount.startsWith("-") ? "" : "+"}{exact(e.amount)}
          </span>,
          <span key="b" className="font-mono">{exact(e.balanceAfter)}</span>,
          <Cause key="r" entry={e} />,
        ].filter(keep),
      }))}
    />
  );
}

/** What moved the balance. An order is a page of its own; anything else is named by its id. */
function Cause({ entry }: { entry: Entry }) {
  if (!entry.referenceType || !entry.referenceId) return <span className="text-dim">—</span>;
  const label = `${entry.referenceType.charAt(0)}${entry.referenceType.slice(1).toLowerCase()} ${entry.referenceId.slice(0, 8)}`;
  return entry.referenceType === "ORDER"
    ? <Link href={`/orders/${entry.referenceId}`} className="font-mono text-muted hover:text-accent">{label}</Link>
    : <span className="font-mono text-muted" title={entry.referenceId}>{label}</span>;
}
