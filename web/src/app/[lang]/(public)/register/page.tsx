import { RegisterForm } from "@/features/auth/RegisterForm";

import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/register">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).register.title };
}

export default function RegisterPage() {
  return <RegisterForm />;
}
