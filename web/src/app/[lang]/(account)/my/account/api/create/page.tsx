import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { CreateApiKey } from "@/features/my/account/CreateApiKey";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/api/create">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).apiKeys.create };
}

export default function Page() {
  return <CreateApiKey />;
}
