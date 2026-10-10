import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { SubAccounts } from "@/features/my/subaccounts/SubAccounts";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/sub-accounts">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.subAccountManagement };
}

export default function Page() {
  return <SubAccounts />;
}
