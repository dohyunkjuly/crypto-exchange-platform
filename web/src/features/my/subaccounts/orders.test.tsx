import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  fetchSubAccounts: vi.fn(), fetchOrders: vi.fn(), fetchOrderHistory: vi.fn(), fetchMyTrades: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }), usePathname: () => "/en/my/sub-accounts/orders",
  useSearchParams: () => new URLSearchParams(""),
}));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/markets/api", async (orig) => ({ ...(await orig<object>()), fetchSymbols: vi.fn().mockResolvedValue([]) }));
const { SubOrders } = await import("./Orders");

const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  api.fetchOrders.mockResolvedValue([]);
  api.fetchOrderHistory.mockResolvedValue([]);
  api.fetchMyTrades.mockResolvedValue([]);
});
afterEach(() => vi.clearAllMocks());

describe("Sub-account order management", () => {
  it("with no sub-accounts, renders the whole page and asks for none of anyone's orders", async () => {
    api.fetchSubAccounts.mockResolvedValue([]);
    withClient(<SubOrders />);

    // The screen they are about to use, not a page that is only a sentence.
    expect(await screen.findByRole("tab", { name: /Open orders/ })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Pair" })).toBeInTheDocument();
    expect(screen.getByText("No open orders")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Sub-account" })).toBeDisabled();

    // Nothing is fetched: a read of an account that does not exist would be an error, and
    // asking with no id at all would answer with the master's own orders.
    expect(api.fetchOrders).not.toHaveBeenCalled();
  });

  it("with a sub, reads that sub's orders", async () => {
    api.fetchSubAccounts.mockResolvedValue([{ id: 2, email: "e", label: "arb bot", frozen: false, createdAt: "2026-09-01T00:00:00Z" }]);
    withClient(<SubOrders />);

    expect(await screen.findByRole("columnheader", { name: "Pair" })).toBeInTheDocument();
    expect(api.fetchOrders.mock.calls[0][1]).toBe(2);
  });
});
