import { RedirectIfSignedIn } from "@/features/auth/RedirectIfSignedIn";
import { AuthShell } from "@/features/shell/AuthShell";
import type { Locale } from "@/lib/i18n/locales";

/** Signed-out pages in the auth shell. A live session is sent on instead (RedirectIfSignedIn). */
export default async function PublicLayout({ children, params }: LayoutProps<"/[lang]">) {
  const { lang } = await params;
  return <AuthShell lang={lang as Locale}><RedirectIfSignedIn>{children}</RedirectIfSignedIn></AuthShell>;
}
