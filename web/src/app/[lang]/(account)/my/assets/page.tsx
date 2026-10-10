import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { AssetsTable } from "@/features/my/assets/AssetsTable";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/assets">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).assets.title };
}

export default function Page() {
  return <AssetsTable />;
}
