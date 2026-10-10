import { VerifyEmailForm } from "@/features/auth/VerifyEmailForm";

import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/verify-email">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).verify.title };
}

export default function VerifyEmailPage() {
  return <VerifyEmailForm />;
}
