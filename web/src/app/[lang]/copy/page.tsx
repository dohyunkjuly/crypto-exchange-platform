import { Leaders } from "@/features/copy/Leaders";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/copy">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).copy.title };
}

export default function Page() {
  return <Leaders />;
}
