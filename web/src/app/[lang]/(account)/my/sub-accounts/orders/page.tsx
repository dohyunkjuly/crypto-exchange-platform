import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { SubOrders } from "@/features/my/subaccounts/Orders";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/sub-accounts/orders">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.subOrders };
}

export default function Page() {
  return <SubOrders />;
}
