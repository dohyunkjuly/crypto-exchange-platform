import { CopyAccount } from "@/features/copy/CopyAccount";
import { RequireSession } from "@/features/auth/RequireSession";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/copy/mine/[id]">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).copy.myCopies };
}

export default async function Page({ params }: PageProps<"/[lang]/copy/mine/[id]">) {
  const { id } = await params;
  return (
    <RequireSession>
      <CopyAccount id={id} />
    </RequireSession>
  );
}
