import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { Authenticator } from "@/features/my/account/Authenticator";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/security/authenticator">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).authenticator.title };
}

export default function Page() {
  return <Authenticator />;
}
