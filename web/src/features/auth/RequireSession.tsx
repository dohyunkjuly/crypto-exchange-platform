"use client";

import { useRouter } from "next/navigation";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { isUnauthenticated, useSessionQuery } from "./session";
import { useDescribe } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";

/**
 * Renders its children only for a signed-in viewer, and redirects anyone else to sign in.
 * A signed-in viewer whose email is unconfirmed is sent to verification instead, from every
 * page but that one -- so registering, or logging in later without having verified, both end
 * up at the code. The server gates trading and KYC on the same flag; this is the funnel.
 *
 * **This is a convenience, not a security boundary.** Every endpoint behind it enforces its own
 * session server-side; what this prevents is a signed-out user staring at an empty shell. A
 * cookie's presence is never consulted -- the question is answered by `GET /bff/auth/me`
 * (AGENTS.md).
 */
export function RequireSession({ children }: { children: React.ReactNode }) {
  const t = useT();
  const locale = useLocale();
  const describe = useDescribe();
  const router = useRouter();
  const pathname = usePathname();
  const { data: user, isPending, error } = useSessionQuery();

  const signedOut = !isPending && isUnauthenticated(error);
  const verifyPath = localePath(locale, "/verify-email");
  const needsVerification = !!user && !user.emailVerified && pathname !== verifyPath;

  useEffect(() => {
    if (signedOut) {
      // Carries where they were going, so signing in returns them there rather than to a
      // default page. `replace`, not `push`: Back should not land on a page that bounced.
      router.replace(localePath(locale, `/login?next=${encodeURIComponent(pathname)}`));
    } else if (needsVerification) {
      router.replace(verifyPath);
    }
  }, [signedOut, needsVerification, verifyPath, pathname, router, locale]);

  if (user && !needsVerification) {
    return children;
  }

  // An unreachable backend is not a signed-out user, and redirecting to a sign-in page that
  // also cannot work would be a loop. Say what happened instead.
  if (!isPending && !signedOut) {
    return (
      <main className="m-auto max-w-sm p-8 text-center">
        <p role="alert" className="text-sm">
          {describe(error) ?? t("common.loadFailed")}
        </p>
      </main>
    );
  }

  // Covers both the first load and the moment between deciding to redirect and arriving.
  return (
    <main className="m-auto p-8" aria-busy>
      <p className="text-sm text-muted">{t("common.loading")}</p>
    </main>
  );
}
