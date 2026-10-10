import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { Orders } from "@/features/my/orders/Orders";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/orders">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.orders };
}

export default function Page() {
  return <Orders />;
}
