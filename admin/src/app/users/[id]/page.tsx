import type { Metadata } from "next";
import { Shell } from "@/components/Shell";
import { UserDetail } from "@/features/users/UserDetail";

export const metadata: Metadata = { title: "Customer" };

/** No page title: a customer file leads with the customer. */
export default async function UserPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Shell>
      <UserDetail id={Number(id)} />
    </Shell>
  );
}
