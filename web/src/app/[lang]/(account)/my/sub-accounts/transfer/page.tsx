import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { Transfer } from "@/features/my/subaccounts/Transfer";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/sub-accounts/transfer">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).subAccounts.transferTitle };
}

export default function Page() {
  return <Transfer />;
}
