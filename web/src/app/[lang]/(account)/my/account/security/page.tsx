import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { Security } from "@/features/my/account/Security";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/security">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.security };
}

export default function Page() {
  return <Security />;
}
