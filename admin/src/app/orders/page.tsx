import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Orders } from "@/features/orders/Orders";

export const metadata: Metadata = { title: "Orders" };

export default function OrdersPage() {
  return (
    <Shell title="Orders">
      <Suspense>
        <Orders />
      </Suspense>
    </Shell>
  );
}
