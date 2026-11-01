import type { Metadata } from "next";
import { Suspense } from "react";
import { Admins } from "@/features/admins/Admins";

export const metadata: Metadata = { title: "Admins" };

export default function AdminsPage() {
  return (
    <Suspense>
      <Admins />
    </Suspense>
  );
}
