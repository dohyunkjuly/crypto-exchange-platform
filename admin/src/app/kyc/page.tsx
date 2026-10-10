import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Kyc } from "@/features/kyc/Kyc";

export const metadata: Metadata = { title: "KYC review" };

export default function KycPage() {
  return (
    <Shell title="KYC review">
      <Suspense>
        <Kyc />
      </Suspense>
    </Shell>
  );
}
