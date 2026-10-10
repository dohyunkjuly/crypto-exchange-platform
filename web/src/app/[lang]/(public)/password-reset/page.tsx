import { PasswordResetForm } from "@/features/auth/PasswordResetForm";

import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/password-reset">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).reset.title };
}

export default function PasswordResetPage() {
  return <PasswordResetForm />;
}
