import { Mark, Wordmark } from "@/components/ui/Brand";
import { ThemeToggle } from "@/features/shell/ThemeToggle";
import { LocaleSwitcher } from "@/features/shell/LocaleSwitcher";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import type { Locale } from "@/lib/i18n/locales";
import { getMessages } from "@/lib/i18n/server";
import { format as fill } from "@/lib/i18n/format";
import { EXCHANGE_NAME } from "@/lib/env";

/**
 * The look of every page on the way in: brand panel left, one form right, the panel dropping
 * away below `lg`. No app navigation -- a page offering doors that are all locked, or that
 * cannot be walked through until the form is done. Sign-in, sign-up, password reset and email
 * verification share it; who may see each is the route group's business, not this one's.
 */
export async function AuthShell({ lang, children }: { lang: Locale; children: React.ReactNode }) {
  const m = await getMessages(lang);
  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <aside className="relative hidden overflow-hidden border-r border-border bg-surface lg:flex lg:flex-col">
        <div aria-hidden className="dotgrid absolute inset-0" />
        <div className="relative flex h-full flex-col p-10 xl:p-14">
          <LocaleLink href="/" aria-label={fill(m.brand.home, { name: EXCHANGE_NAME })} className="w-fit"><Wordmark height={20} /></LocaleLink>
          <div className="my-auto max-w-md py-16">
            <Mark size={128} className="mb-10" />
            <h2 className="text-3xl font-semibold tracking-tight xl:text-4xl">{fill(m.authPanel.headline, { name: EXCHANGE_NAME })}</h2>
          </div>
          <p className="text-xs text-dim">{fill(m.brand.footer, { name: EXCHANGE_NAME })}</p>
        </div>
      </aside>
      <div className="flex min-w-0 flex-col">
        <header className="flex h-16 items-center justify-between px-5">
          <LocaleLink href="/" aria-label={fill(m.brand.home, { name: EXCHANGE_NAME })} className="lg:invisible"><Wordmark height={20} /></LocaleLink>
          <div className="flex items-center gap-2"><LocaleSwitcher /><ThemeToggle /></div>
        </header>
        <main className="flex flex-1 items-start justify-center px-4 pb-16 pt-4 sm:pt-10 lg:items-center lg:pt-0">
          <div className="w-full max-w-[400px] rounded-lg border border-border bg-surface p-6 sm:p-8 lg:border-0 lg:bg-transparent lg:p-0">{children}</div>
        </main>
        <footer className="px-5 py-4 text-center text-xs text-dim lg:hidden">{fill(m.brand.footer, { name: EXCHANGE_NAME })}</footer>
      </div>
    </div>
  );
}
