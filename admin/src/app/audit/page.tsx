import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Audit } from "@/features/audit/Audit";

export const metadata: Metadata = { title: "Audit log" };

export default function AuditPage() {
  return (
    <Shell title="Audit log">
      <Suspense>
        <Audit />
      </Suspense>
    </Shell>
  );
}
