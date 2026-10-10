import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MyTrade } from "../shared/api";

const api = vi.hoisted(() => ({ fetchMyTrades: vi.fn() }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/markets/api", async (orig) => ({ ...(await orig<object>()), fetchSymbols: vi.fn().mockResolvedValue([]) }));
const { TradeHistory } = await import("./Orders");

const trade = (i: number): MyTrade => ({
  tradeId: `t${i}`, symbol: "BTCUSDT", orderId: "o", orderType: "LIMIT", price: "100", quantity: "1", quoteQuantity: "100",
  side: "BUY", isMaker: true, fee: "0", feeAsset: "USDT", time: "2026-10-01T00:00:00Z",
});
const page = (n: number) => Array.from({ length: n }, (_, i) => trade(i));
const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
afterEach(() => vi.resetAllMocks());

describe("TradeHistory paging", () => {
  // Twenty on the first page, three on the second: the second is the end.
  beforeEach(() => api.fetchMyTrades.mockImplementation(({ page: p }: { page: number }) => Promise.resolve(p === 0 ? page(20) : page(3))));

  it("asks for 20 rows a page, offers Next while the page came back full, and a filter change returns to the first page", async () => {
    const user = userEvent.setup();
    withClient(<TradeHistory />);
    expect(await screen.findByText("Page 1")).toBeInTheDocument();
    expect(api.fetchMyTrades).toHaveBeenLastCalledWith({ page: 0, size: 20 }, expect.anything(), expect.anything(), undefined);
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Page 2")).toBeInTheDocument();
    expect(api.fetchMyTrades).toHaveBeenLastCalledWith({ page: 1, size: 20 }, expect.anything(), expect.anything(), undefined);
    // Three rows: the end, so Next is off.
    expect(await screen.findByRole("button", { name: "Next" })).toBeDisabled();

    await user.selectOptions(screen.getByRole("combobox", { name: "Side" }), "BUY");
    expect(await screen.findByText("Page 1")).toBeInTheDocument();
    expect(api.fetchMyTrades).toHaveBeenLastCalledWith({ page: 0, size: 20 }, expect.objectContaining({ side: "BUY" }), expect.anything(), undefined);
  });

  it("shows no pager at all when the first page is also the last", async () => {
    api.fetchMyTrades.mockResolvedValue(page(3));
    withClient(<TradeHistory />);
    expect(await screen.findAllByText("BTCUSDT")).toHaveLength(3);
    expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
  });

  it("the trade screen's compact panel takes the server's 50", async () => {
    withClient(<TradeHistory compact lockSymbol="BTCUSDT" />);
    expect(await screen.findAllByText("BTCUSDT")).toHaveLength(20);
    expect(api.fetchMyTrades).toHaveBeenLastCalledWith({ page: 0, size: 50 }, expect.anything(), expect.anything(), undefined);
  });
});
