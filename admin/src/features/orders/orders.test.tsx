import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchOrders: vi.fn(), fetchOrder: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  usePathname: () => "/orders",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { Orders } = await import("./Orders");
const { OrderDetail } = await import("./OrderDetail");

const order = (over: Partial<Record<string, unknown>> = {}) => ({
  id: "0190a000-0000-7000-8000-000000000001", symbol: "BTCUSDT", base: "BTC", quote: "USDT", userId: 7, userEmail: "ann@x.dev",
  side: "BUY", type: "LIMIT", status: "NEW", price: "65000.5", stopPrice: null, quantity: "0.25", quoteOrderQty: null,
  filled: "0", quoteFilled: "0", clientOrderId: null, createdAt: "2026-09-20T10:00:00Z", updatedAt: "2026-09-20T10:00:00Z", ...over,
});

beforeEach(() => {
  nav.query = "";
  api.fetchOrders.mockResolvedValue({ items: [order(), order({ id: "done", status: "FILLED", filled: "0.25" })], total: 2, page: 0, size: 50 });
  api.fetchOrder.mockResolvedValue({ order: order(), fills: [] });
});
afterEach(() => vi.clearAllMocks());

describe("Orders list", () => {
  it("sends the filters from the URL, a day range as local midnights with the last day included", async () => {
    nav.query = "customer=ann%40x.dev&state=open&from=2026-09-01&to=2026-09-01";
    render(<Orders />);
    await screen.findAllByText("BTCUSDT");
    const [filters] = api.fetchOrders.mock.calls[0];
    expect(filters).toMatchObject({ customer: "ann@x.dev", state: "open", from: new Date(2026, 8, 1).toISOString(),
                                    to: new Date(2026, 8, 2).toISOString() });
  });

  it("keeps a search in the URL", async () => {
    const u = userEvent.setup();
    render(<Orders />);
    await u.type(await screen.findByRole("textbox", { name: "Customer" }), "7");
    await u.click(screen.getByRole("button", { name: "Search" }));
    expect(nav.replace).toHaveBeenCalledWith("/orders?customer=7");
  });

  it("offers no way to cancel an order", async () => {
    render(<Orders />);
    await screen.findAllByText("ann@x.dev");
    expect(screen.queryByRole("button", { name: /Cancel/ })).not.toBeInTheDocument();
  });

  it("keeps its columns with no match", async () => {
    nav.query = "symbol=NOPE";
    api.fetchOrders.mockResolvedValue({ items: [], total: 0, page: 0, size: 50 });
    render(<Orders />);
    expect(await screen.findByText("No order matches.")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Status" })).toBeInTheDocument();
  });

  it("shows an order sized in quote by its quote amount", async () => {
    api.fetchOrders.mockResolvedValue({
      items: [order({ type: "MARKET", price: null, quantity: null, quoteOrderQty: "100", quoteFilled: "40" })], total: 1, page: 0, size: 50,
    });
    render(<Orders />);
    expect(await screen.findByText("100 USDT")).toBeInTheDocument();
    expect(screen.getAllByText("Market")).toHaveLength(2);
    expect(screen.getByText("40")).toBeInTheDocument();
  });
});

describe("Order detail", () => {
  it("shows each fill from this order's side, fee with its asset", async () => {
    api.fetchOrder.mockResolvedValue({
      order: order({ status: "PARTIALLY_FILLED", filled: "0.1" }),
      fills: [{ tradeId: "t1", price: "65000", quantity: "0.1", quoteQuantity: "6500", maker: true, fee: "0.0001",
                feeAsset: "BTC", executedAt: "2026-09-20T10:01:00Z" }],
    });
    render(<OrderDetail id="x" />);
    const fill = (await screen.findByText("Maker")).closest("tr")!;
    expect(within(fill).getByText("6,500")).toBeInTheDocument();
    expect(within(fill).getByText("BTC")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Cancel/ })).not.toBeInTheDocument();
  });

  it("says when nothing has filled", async () => {
    api.fetchOrder.mockResolvedValue({ order: order({ status: "PENDING_CANCEL" }), fills: [] });
    render(<OrderDetail id="x" />);
    expect(await screen.findByText("Cancelling")).toBeInTheDocument();
    expect(screen.getByText("Nothing has filled.")).toBeInTheDocument();
  });
});
