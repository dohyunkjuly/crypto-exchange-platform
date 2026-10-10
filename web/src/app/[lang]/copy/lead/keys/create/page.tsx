import { LeadKeyCreate } from "@/features/copy/LeadKeys";
import { RequireSession } from "@/features/auth/RequireSession";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/copy/lead/keys/create">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).apiKeys.create };
}

export default function Page() {
  return (
    <RequireSession>
      <LeadKeyCreate />
    </RequireSession>
  );
}
