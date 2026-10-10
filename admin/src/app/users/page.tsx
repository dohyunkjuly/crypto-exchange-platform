import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/Shell";
import { Users } from "@/features/users/Users";

export const metadata: Metadata = { title: "Customers" };

export default function UsersPage() {
  return (
    <Shell title="Customers">
      <Suspense>
        <Users />
      </Suspense>
    </Shell>
  );
}
