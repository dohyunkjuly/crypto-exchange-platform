import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Fees } from "@/features/fees/Fees";

export const metadata: Metadata = { title: "Fees" };

export default function FeesPage() {
  return (
    <Shell title="Fees">
      <Suspense>
        <Fees />
      </Suspense>
    </Shell>
  );
}
