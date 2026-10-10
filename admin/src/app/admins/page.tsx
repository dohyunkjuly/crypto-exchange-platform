import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Admins } from "@/features/admins/Admins";

export const metadata: Metadata = { title: "Admins" };

export default function AdminsPage() {
  return (
    <Shell title="Admins">
      <Suspense>
        <Admins />
      </Suspense>
    </Shell>
  );
}
