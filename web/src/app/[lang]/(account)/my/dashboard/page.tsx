import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { Dashboard } from "@/features/my/dashboard/Dashboard";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/dashboard">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).dashboard.title };
}

export default function Page() {
  return <Dashboard />;
}
