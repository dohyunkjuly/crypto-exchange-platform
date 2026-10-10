"use client";

import Link from "next/link";
import { Fact } from "@/components/Fact";
import { Table } from "@/components/Table";
import { exact } from "@/lib/money";
import { useLoad } from "@/lib/search";
import { when } from "@/lib/time";
import { fetchOrder } from "./api";
import { OrderStatusPill } from "./OrderStatusPill";
import { Side, size, typeName } from "./OrdersTable";

/** One order and every fill it took part in, from its own side: its fee, and whether it made or took. */
export function OrderDetail({ id }: { id: string }) {
  const { data, error } = useLoad(fetchOrder, id);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!data) return <p className="text-muted" aria-busy>Loading…</p>;

  const o = data.order;
  const dash = <span className="text-dim">—</span>;

  return (
    <>
      <section className="card px-4 py-4 sm:px-5 sm:py-[18px] flex items-start gap-5">
        <div className="flex-1 min-w-0 flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="m-0 text-lg font-semibold"><Side side={o.side} /> {o.symbol}</h1>
            <OrderStatusPill status={o.status} />
            <span className="text-[13px] text-muted">{typeName(o.type)}</span>
          </div>
          <dl className="m-0 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4">
            <Fact label="Customer"><Link href={`/users/${o.userId}`}>{o.userEmail ?? `#${o.userId}`}</Link></Fact>
            <Fact label="Price">{o.price ? <span className="font-mono">{exact(o.price)}</span> : <span className="text-muted">Market</span>}</Fact>
            <Fact label="Stop price">{o.stopPrice ? <span className="font-mono">{exact(o.stopPrice)}</span> : dash}</Fact>
            <Fact label="Amount"><span className="font-mono">{size(o)}</span></Fact>
            <Fact label={`Filled (${o.base})`}><span className="font-mono">{exact(o.filled)}</span></Fact>
            <Fact label={`Filled (${o.quote})`}><span className="font-mono">{exact(o.quoteFilled)}</span></Fact>
            <Fact label="Placed"><span className="font-mono">{when(o.createdAt)}</span></Fact>
            <Fact label="Last change"><span className="font-mono">{when(o.updatedAt)}</span></Fact>
            <Fact label="Client order id">{o.clientOrderId ? <span className="font-mono">{o.clientOrderId}</span> : dash}</Fact>
            <div className="col-span-2 sm:col-span-3 min-w-0">
              <Fact label="Order id"><span className="font-mono">{o.id}</span></Fact>
            </div>
          </dl>
        </div>
      </section>

      <h2 className="m-0 text-base font-semibold">Fills</h2>
      <Table
        empty="Nothing has filled."
        right={[1, 2, 3, 5]}
        head={["Time", "Price", `Quantity (${o.base})`, `Total (${o.quote})`, "Role", "Fee"]}
        rows={data.fills.map((f) => ({
          key: f.tradeId,
          cells: [
            <span key="t" className="font-mono text-muted">{when(f.executedAt)}</span>,
            <span key="p" className="font-mono">{exact(f.price)}</span>,
            <span key="q" className="font-mono">{exact(f.quantity)}</span>,
            <span key="v" className="font-mono">{exact(f.quoteQuantity)}</span>,
            <span key="r" className="text-muted">{f.maker ? "Maker" : "Taker"}</span>,
            <span key="f" className="font-mono">{exact(f.fee)} <span className="text-muted">{f.feeAsset}</span></span>,
          ],
        }))}
      />
    </>
  );
}
