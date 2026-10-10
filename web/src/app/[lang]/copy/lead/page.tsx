import { Lead } from "@/features/copy/Lead";
import { RequireSession } from "@/features/auth/RequireSession";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/copy/lead">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).copy.leadAccount };
}

export default function Page() {
  return (
    <RequireSession>
      <Lead />
    </RequireSession>
  );
}
