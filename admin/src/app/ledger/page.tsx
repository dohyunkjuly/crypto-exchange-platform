import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Ledger } from "@/features/ledger/Ledger";

export const metadata: Metadata = { title: "Ledger" };

export default function LedgerPage() {
  return (
    <Shell title="Ledger">
      <Suspense>
        <Ledger />
      </Suspense>
    </Shell>
  );
}
