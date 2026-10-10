import type { Metadata } from "next";
import { Shell } from "@/components/Shell";
import { MarketDetail } from "@/features/markets/MarketDetail";

export const metadata: Metadata = { title: "Market" };

/** No page title: a market leads with its name. */
export default async function MarketPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Shell>
      <MarketDetail id={id} />
    </Shell>
  );
}
