import type { Metadata } from "next";
import { Shell } from "@/components/Shell";
import { Dashboard } from "@/features/dashboard/Dashboard";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <Shell title="Dashboard">
      <Dashboard />
    </Shell>
  );
}
