import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { SubApi } from "@/features/my/subaccounts/Api";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/sub-accounts/api">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.subApi };
}

export default function Page() {
  return <SubApi />;
}
