import { Faq } from "@/components/ui/Faq";
import { AppFooter } from "@/features/shell/AppFooter";
import { AppHeader } from "@/features/shell/AppHeader";
import { Home } from "@/features/home/Home";
import { MarketsTable } from "@/features/markets/MarketsTable";
import { getMessages } from "@/lib/i18n/server";
import type { Locale } from "@/lib/i18n/locales";

export default async function HomePage({ params }: PageProps<"/[lang]">) {
  const { lang } = await params;
  const m = await getMessages(lang as Locale);
  return (
    <div className="flex min-h-dvh flex-col">
      <AppHeader />
      <main className="mx-auto w-full min-w-0 max-w-7xl flex-1 px-5">
        <Home />
        <section id="markets" className="py-24">
          <h2 className="mb-10 text-center text-3xl font-semibold tracking-tight sm:text-5xl">{m.home.markets}</h2>
          <MarketsTable />
        </section>
        <div className="pb-24">
          <Faq title={m.faq.title} items={([1, 2, 3, 4] as const).map((n) => ({ q: m.faq[`q${n}`], a: m.faq[`a${n}`] }))} />
        </div>
      </main>
      <AppFooter />
    </div>
  );
}
