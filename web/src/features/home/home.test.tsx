import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const markets = vi.hoisted(() => ({ fetchSymbols: vi.fn(), fetchTickers: vi.fn() }));
const session = vi.hoisted(() => ({ status: "signedOut" as "signedIn" | "signedOut" }));
vi.mock("@/features/markets/api", async (orig) => ({ ...(await orig<object>()), ...markets }));
vi.mock("@/features/auth/session", () => ({ useSession: () => ({ status: session.status, user: { id: 1 } }) }));
const { Home } = await import("./Home");

const market = (base: string) => ({ symbol: `${base}USDT`, baseAsset: base, quoteAsset: "USDT", status: "TRADING", priceTick: "0.01", qtyStep: "0.001",
  minQty: "0.001", maxQty: "1000", minNotional: "5", baseDecimals: 8, quoteDecimals: 2, marketOrdersEnabled: true, stopOrdersEnabled: true, depthGroupings: [] });
const ticker = (base: string, last: string, change: string) => ({ symbol: `${base}USDT`, lastPrice: last, openPrice: last, highPrice: last, lowPrice: last,
  priceChange: "0", priceChangePercent: change, volume: "1", quoteVolume: "1" });
const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};

beforeEach(() => {
  session.status = "signedOut";
  markets.fetchSymbols.mockResolvedValue([market("BTC"), market("ETH"), market("NEW")]);
  // Busiest first, as the API orders them; NEW has never traded.
  markets.fetchTickers.mockResolvedValue([ticker("BTC", "60000", "1.5"), ticker("ETH", "3000", "-2.25"), ticker("NEW", "0", "0")]);
});
afterEach(() => vi.clearAllMocks());

describe("Home", () => {
  // First: the favorites store reads the browser once and keeps it, so the starred pair must be set before any test has read.
  it("a signed-in customer is sent to trade the busiest market; the card's Favorites tab reads the starred pairs", async () => {
    const user = userEvent.setup();
    session.status = "signedIn";
    localStorage.setItem("favorite-pairs", "ETHUSDT");
    withClient(<Home />);
    await screen.findAllByRole("link", { name: /\/ USDT/ });
    expect(screen.getAllByRole("link", { name: "Trade" })[0]).toHaveAttribute("href", "/en/trade/BTCUSDT");
    expect(screen.queryByRole("link", { name: "Create account" })).toBeNull();
    await user.click(screen.getByRole("tab", { name: "Favorites" }));
    const rows = screen.getAllByRole("link", { name: /\/ USDT/ });
    expect(rows.map((r) => r.textContent?.slice(0, 3))).toEqual(["ETH"]);
  });

  it("offers a visitor an account and shows the markets live, busiest first, leaving out one that never traded", async () => {
    withClient(<Home />);
    expect(await screen.findByRole("link", { name: "Create account" })).toHaveAttribute("href", "/en/register");
    const card = screen.getByRole("tab", { name: "Spot" }).closest("div")!.parentElement!.parentElement!;
    const rows = await within(card).findAllByRole("link", { name: /\/ USDT/ });
    expect(rows.map((r) => r.textContent?.slice(0, 3))).toEqual(["BTC", "ETH"]);
    expect(within(rows[0]).getByText("+1.5%")).toHaveClass("text-up");
    expect(within(rows[1]).getByText("-2.25%")).toHaveClass("text-down");
    // The portfolio grid counts the same two, and says so.
    expect(screen.getByText("2 markets quoted in USDT, each a click from a chart, a book and an order form.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Trade BTC/USDT" })).toHaveAttribute("href", "/en/trade/BTCUSDT");
    expect(screen.queryByRole("link", { name: "Trade NEW/USDT" })).toBeNull();
  });
});
