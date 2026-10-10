import { LoginForm } from "@/features/auth/LoginForm";

import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/login">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).login.title };
}

export default function LoginPage() {
  return <LoginForm />;
}
