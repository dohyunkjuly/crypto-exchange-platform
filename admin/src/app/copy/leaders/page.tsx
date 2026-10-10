import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Leaders } from "@/features/copy/Leaders";

export const metadata: Metadata = { title: "Copy leaders" };

export default function CopyLeadersPage() {
  return (
    <Shell title="Copy leaders">
      <Suspense>
        <Leaders />
      </Suspense>
    </Shell>
  );
}
