import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Markets } from "@/features/markets/Markets";

export const metadata: Metadata = { title: "Markets" };

export default function MarketsPage() {
  return (
    <Shell title="Markets">
      <Suspense>
        <Markets />
      </Suspense>
    </Shell>
  );
}
