import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchTrades: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  usePathname: () => "/trades",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { Trades } = await import("./Trades");
const { CustomerTrades } = await import("./CustomerTrades");

const trade = (over: Partial<Record<string, unknown>> = {}) => ({
  id: "t1", symbol: "BTCUSDT", base: "BTC", quote: "USDT", price: "65000", quantity: "0.5", quoteQuantity: "32500",
  buyer: { userId: 7, email: "ann@x.dev", orderId: "0190aaaa-0000-7000-8000-000000000001", fee: "0.0005", feeAsset: "BTC" },
  seller: { userId: 8, email: "bob@x.dev", orderId: "0190bbbb-0000-7000-8000-000000000002", fee: "0", feeAsset: null },
  buyerIsMaker: false, executedAt: "2026-09-20T10:00:00Z", ...over,
});

beforeEach(() => {
  nav.query = "";
  api.fetchTrades.mockResolvedValue({ items: [trade()], page: 0, size: 50, more: true });
});
afterEach(() => vi.clearAllMocks());

describe("Trades", () => {
  it("sends the filters from the URL, the last day included, and shows both sides", async () => {
    nav.query = "symbol=BTCUSDT&from=2026-09-01&to=2026-09-01";
    render(<Trades />);
    const row = (await screen.findByText("ann@x.dev")).closest("tr")!;
    expect(within(row).getByText("bob@x.dev")).toBeInTheDocument();
    expect(within(row).getByText("32,500")).toBeInTheDocument();
    expect(api.fetchTrades).toHaveBeenCalledWith(
      { customer: "", symbol: "BTCUSDT", from: new Date(2026, 8, 1).toISOString(), to: new Date(2026, 8, 2).toISOString() }, 0, 50);
  });

  it("pages forward while there is more, without a total", async () => {
    const u = userEvent.setup();
    render(<Trades />);
    await u.click(await screen.findByRole("button", { name: "Next" }));
    expect(nav.replace).toHaveBeenCalledWith("/trades?page=1");
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
  });

  it("offers no pager when one page holds everything", async () => {
    api.fetchTrades.mockResolvedValue({ items: [trade()], page: 0, size: 50, more: false });
    render(<Trades />);
    await screen.findByText("ann@x.dev");
    expect(screen.queryByRole("button", { name: "Next" })).not.toBeInTheDocument();
  });

  it("keeps its columns with no match", async () => {
    nav.query = "customer=nobody";
    api.fetchTrades.mockResolvedValue({ items: [], page: 0, size: 50, more: false });
    render(<Trades />);
    expect(await screen.findByText("No trade matches.")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Seller" })).toBeInTheDocument();
  });
});

describe("A customer's trades", () => {
  it("shows the customer's own side: what they did, their fee, their role, and who was opposite", async () => {
    api.fetchTrades.mockResolvedValue({ items: [trade({ buyerIsMaker: true })], page: 0, size: 20, more: false });
    render(<CustomerTrades userId={8} />);
    const row = (await screen.findByText("Sell")).closest("tr")!;
    expect(within(row).getByText("Taker")).toBeInTheDocument();
    expect(within(row).getByText("ann@x.dev")).toBeInTheDocument();
    expect(within(row).getByRole("link", { name: "0190bbbb" })).toHaveAttribute("href", "/orders/0190bbbb-0000-7000-8000-000000000002");
    expect(api.fetchTrades).toHaveBeenCalledWith({ customer: "8", symbol: "", from: "", to: "" }, 0, 20);
  });
});
