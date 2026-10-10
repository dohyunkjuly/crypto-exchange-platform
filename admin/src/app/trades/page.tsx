import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Trades } from "@/features/trades/Trades";

export const metadata: Metadata = { title: "Trades" };

export default function TradesPage() {
  return (
    <Shell title="Trades">
      <Suspense>
        <Trades />
      </Suspense>
    </Shell>
  );
}
