import { RequireSession } from "@/features/auth/RequireSession";
import { AuthShell } from "@/features/shell/AuthShell";
import type { Locale } from "@/lib/i18n/locales";

/**
 * Email verification: signed in, but not yet allowed in. The auth shell, not the app's --
 * a navigation bar over a page you cannot leave until the code is typed is a row of locked
 * doors -- and RequireSession rather than RedirectIfSignedIn, since the viewer has a session
 * and that is the point.
 */
export default async function VerifyLayout({ children, params }: LayoutProps<"/[lang]">) {
  const { lang } = await params;
  return <AuthShell lang={lang as Locale}><RequireSession>{children}</RequireSession></AuthShell>;
}
