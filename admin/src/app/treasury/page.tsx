import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Treasury } from "@/features/treasury/Treasury";

export const metadata: Metadata = { title: "Treasury" };

export default function TreasuryPage() {
  return (
    <Shell title="Treasury">
      {/* The view lives in the URL, and reading it needs a boundary to prerender behind. */}
      <Suspense>
        <Treasury />
      </Suspense>
    </Shell>
  );
}
