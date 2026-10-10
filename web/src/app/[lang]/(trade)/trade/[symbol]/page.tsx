import { AppHeader } from "@/features/shell/AppHeader";
import { TradeScreen } from "@/features/trade/TradeScreen";

export async function generateMetadata({ params }: PageProps<"/[lang]/trade/[symbol]">) {
  const { symbol } = await params;
  return { title: symbol.toUpperCase() };
}

export default async function TradePage({ params }: PageProps<"/[lang]/trade/[symbol]">) {
  const { symbol } = await params;
  return (
    <div className="flex min-h-dvh flex-col">
      <AppHeader />
      <TradeScreen symbol={symbol} />
    </div>
  );
}
