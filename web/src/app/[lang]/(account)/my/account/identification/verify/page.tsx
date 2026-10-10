import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";
import { VerifyIdentity } from "@/features/my/account/VerifyIdentity";

export async function generateMetadata({ params }: PageProps<"/[lang]/my/account/identification/verify">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).kyc.verifyTitle };
}

export default function Page() {
  return <VerifyIdentity />;
}
