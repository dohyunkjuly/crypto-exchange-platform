import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { SubTransfers } from "@/features/my/subaccounts/TransferHistory";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/sub-accounts/transfers">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.subTransfers };
}

export default function Page() {
  return <SubTransfers />;
}
