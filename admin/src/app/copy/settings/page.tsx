import type { Metadata } from "next";
import { Shell } from "@/components/Shell";
import { Settings } from "@/features/copy/Settings";

export const metadata: Metadata = { title: "Copy settings" };

export default function CopySettingsPage() {
  return (
    <Shell title="Copy settings">
      <Settings />
    </Shell>
  );
}
