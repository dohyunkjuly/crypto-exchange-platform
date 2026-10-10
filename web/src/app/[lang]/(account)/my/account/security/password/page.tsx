import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { ChangePassword } from "@/features/my/account/ChangePassword";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/security/password">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).password.title };
}

export default function Page() {
  return <ChangePassword />;
}
