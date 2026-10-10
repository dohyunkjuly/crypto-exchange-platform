/**
 * Numbered questions, each a row of its own with air around it, the answer folding out under
 * the question. No dividers: the numbers already say where a row starts. A server component,
 * so the home page renders it as static HTML; the copy page passes it strings it has built.
 */
export function Faq({ title, items }: { title: string; items: { q: string; a: string }[] }) {
  return (
    <section id="faq" className="mx-auto w-full max-w-6xl">
      <h2 className="mb-12 text-center text-3xl font-semibold sm:text-4xl">{title}</h2>
      <div className="flex flex-col">
        {items.map((item, i) => (
          <details key={item.q} className="group border-b border-border first:border-t">
            {/* The whole row answers to the pointer, as a table row does: the number, the plus and the
                background move together, so the row reads as one thing to click. */}
            <summary className="-mx-4 flex cursor-pointer list-none items-center gap-5 rounded-lg px-4 py-7 transition-colors hover:bg-surface-raised [&::-webkit-details-marker]:hidden">
              <span aria-hidden className="num flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-border text-sm text-muted transition-colors group-hover:border-border-strong group-hover:text-text">{i + 1}</span>
              <span className="flex-1 text-lg font-medium sm:text-xl">{item.q}</span>
              <span aria-hidden className="text-3xl font-light leading-none text-muted transition-transform group-hover:text-text group-open:rotate-45">+</span>
            </summary>
            <p className="pb-7 pl-14 text-[17px] leading-relaxed text-muted sm:text-lg">{item.a}</p>
          </details>
        ))}
      </div>
    </section>
  );
}
