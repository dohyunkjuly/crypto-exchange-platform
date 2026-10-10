import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { SubAssets } from "@/features/my/subaccounts/Assets";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/sub-accounts/assets">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).menu.subAssets };
}

export default function Page() {
  return <SubAssets />;
}
