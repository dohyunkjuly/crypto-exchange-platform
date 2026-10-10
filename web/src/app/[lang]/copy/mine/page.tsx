import { MyCopies } from "@/features/copy/MyCopies";
import { RequireSession } from "@/features/auth/RequireSession";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/copy/mine">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).copy.myCopies };
}

export default function Page() {
  return (
    <RequireSession>
      <MyCopies />
    </RequireSession>
  );
}
