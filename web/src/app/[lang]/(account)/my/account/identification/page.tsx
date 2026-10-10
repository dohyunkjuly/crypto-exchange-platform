import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { Identification } from "@/features/my/account/Identification";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/identification">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.identification };
}

export default function Page() {
  return <Identification />;
}
