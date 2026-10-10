import { LocaleLink } from "@/lib/i18n/LocaleLink";

/** "‹ Security" above a sub-page's title. */
export function BackLink({ href, label }: { href: string; label: string }) {
  return (
    <LocaleLink href={href} className="flex w-fit items-center gap-1 text-base text-muted hover:text-text">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="m15 18-6-6 6-6" />
      </svg>
      {label}
    </LocaleLink>
  );
}
