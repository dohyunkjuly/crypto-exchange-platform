import type { Metadata } from "next";
import { Shell } from "@/components/Shell";
import { OrderDetail } from "@/features/orders/OrderDetail";

export const metadata: Metadata = { title: "Order" };

/** No page title: an order leads with what it is. */
export default async function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Shell>
      <OrderDetail id={id} />
    </Shell>
  );
}
