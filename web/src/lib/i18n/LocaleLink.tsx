"use client";

import Link from "next/link";
import { localePath } from "./locales";
import { useLocale } from "./index";

/** `next/link` that prefixes the current locale, so no page hardcodes "/ko/…". */
export function LocaleLink({ href, ...props }: React.ComponentProps<typeof Link> & { href: string }) {
  return <Link {...props} href={localePath(useLocale(), href)} />;
}
