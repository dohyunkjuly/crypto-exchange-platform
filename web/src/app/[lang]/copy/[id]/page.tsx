import { LeaderProfile } from "@/features/copy/LeaderProfile";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export async function generateMetadata({ params }: PageProps<"/[lang]/copy/[id]">) {
  const { lang } = await params;
  return { title: (await getMessages(lang as Locale)).copy.title };
}

export default async function Page({ params, searchParams }: PageProps<"/[lang]/copy/[id]">) {
  const { id } = await params;
  // `?copy=1`: "Copy again" from a closed copy account lands on the form, not the profile.
  const { copy } = await searchParams;
  return <LeaderProfile id={id} startCopying={copy === "1"} />;
}
