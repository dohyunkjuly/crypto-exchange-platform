import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchSubAccounts: vi.fn(), fetchAllSubAccountBalances: vi.fn(), fetchAssets: vi.fn() }));
const markets = vi.hoisted(() => ({ fetchTickers: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/markets/api", async (orig) => ({ ...(await orig<object>()), ...markets }));
const { SubAssets } = await import("./Assets");

const SUBS = [
  { id: 2, email: "ann+sub-a@x.dev", label: "arb bot", frozen: false, createdAt: "2026-09-01T00:00:00Z" },
  { id: 3, email: "ann+sub-b@x.dev", label: "grid bot", frozen: true, createdAt: "2026-09-02T00:00:00Z" },
];
const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  nav.query = "";
  api.fetchSubAccounts.mockResolvedValue(SUBS);
  api.fetchAssets.mockResolvedValue([
    { symbol: "USDT", name: "Tether", assetType: "STABLE", displayDecimals: 2 },
    { symbol: "BTC", name: "Bitcoin", assetType: "COIN", displayDecimals: 5 },
    // Held, but no USDT market prices it: it must still be listed, just without a value.
    { symbol: "XYZ", name: "Xyzzy", assetType: "COIN", displayDecimals: 4 },
  ]);
  api.fetchAllSubAccountBalances.mockResolvedValue({
    2: [{ asset: "USDT", available: "100", locked: "50", total: "150", version: 1 },
        { asset: "BTC", available: "0.5", locked: "0", total: "0.5", version: 1 },
        { asset: "XYZ", available: "9", locked: "0", total: "9", version: 1 }],
    3: [],
  });
  markets.fetchTickers.mockResolvedValue([{ symbol: "BTCUSDT", lastPrice: "40000", priceChangePercent: "1" }]);
});
afterEach(() => vi.clearAllMocks());

describe("Sub-account asset management", () => {
  it("values each sub in USDT, and offers neither direction on a frozen one", async () => {
    withClient(<SubAssets />);

    const arb = (await screen.findByRole("cell", { name: "arb bot" })).closest("tr")!;
    // 150 USDT and 0.5 BTC at 40000; the unpriced XYZ is left out rather than counted as zero.
    expect(within(arb).getByText("20,150.00 USDT")).toBeInTheDocument();
    expect(within(arb).getByRole("link", { name: "Transfer in" })).toHaveAttribute("href", "/en/my/sub-accounts/transfer?to=2");
    expect(within(arb).getByRole("link", { name: "Transfer out" })).toHaveAttribute("href", "/en/my/sub-accounts/transfer?from=2");

    const grid = screen.getByRole("cell", { name: "grid bot" }).closest("tr")!;
    expect(within(grid).getByText("0.00 USDT")).toBeInTheDocument();
    expect(within(grid).queryByRole("link", { name: "Transfer in" })).toBeNull();
  });

  it("opens one sub's coins on Details, unpriced holdings included", async () => {
    const user = userEvent.setup();
    withClient(<SubAssets />);

    const arb = (await screen.findByRole("cell", { name: "arb bot" })).closest("tr")!;
    await user.click(within(arb).getByRole("button", { name: "Details" }));
    expect(nav.replace).toHaveBeenCalledWith("/en/my/sub-accounts/assets?sub=2");
  });

  it("with ?sub=, lists that sub's coins and what each is worth", async () => {
    nav.query = "sub=2";
    withClient(<SubAssets />);

    const btc = (await screen.findByRole("cell", { name: "BTC" })).closest("tr")!;
    expect(within(btc).getByText("Bitcoin")).toBeInTheDocument();
    expect(within(btc).getByText("20,000.00 USDT")).toBeInTheDocument();

    const usdt = screen.getByRole("cell", { name: "USDT" }).closest("tr")!;
    expect(within(usdt).getByText("50.00")).toBeInTheDocument(); // in order

    // No market prices XYZ: the holding is shown, its value is not invented.
    const xyz = screen.getByRole("cell", { name: "XYZ" }).closest("tr")!;
    expect(within(xyz).getByText("—")).toBeInTheDocument();
  });

  it("says a sub holds nothing without taking its columns away", async () => {
    nav.query = "sub=3";
    withClient(<SubAssets />);

    expect(await screen.findByText("No balances yet")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Asset" })).toBeInTheDocument();
  });
});
