import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { ApiKeys } from "@/features/my/account/ApiKeys";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/api">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.api };
}

export default function Page() {
  return <ApiKeys />;
}
