"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useLocale } from "@/lib/i18n";
import { localePath } from "@/lib/i18n/locales";
import { currentNextPath } from "./nextPath";
import { useSessionQuery } from "./session";

/**
 * Keeps a live session off the sign-in pages: a signed-in viewer goes where RequireSession would
 * let them in -- the `next` page or the dashboard, or verification first. Changing a password
 * while signed in is /my/account/security/password, which asks for the second factor up front.
 *
 * Signed-out viewers, and a backend that cannot answer, get the page: the form reports that
 * itself. Like RequireSession, a convenience and not a boundary.
 */
export function RedirectIfSignedIn({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const locale = useLocale();
  const { data: user, isPending } = useSessionQuery();

  useEffect(() => {
    if (user) router.replace(localePath(locale, user.emailVerified ? currentNextPath() : "/verify-email"));
  }, [user, router, locale]);

  // Nothing until the answer is in, so a signed-in viewer never sees a form flash past.
  if (isPending || user) return null;
  return children;
}
