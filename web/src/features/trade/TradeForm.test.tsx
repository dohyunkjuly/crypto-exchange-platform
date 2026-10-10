import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MarketSymbol } from "@/features/markets/api";

const bff = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("@/lib/bff/client", () => bff);
vi.mock("@/features/auth/session", () => ({ useSession: () => ({ status: "signedIn", user: { id: 1, emailVerified: true } }) }));
vi.mock("@/components/ui/Toast", () => ({ useToast: () => vi.fn() }));
const { TradeForm } = await import("./TradeForm");

const market = {
  symbol: "BTCUSDT", baseAsset: "BTC", quoteAsset: "USDT", status: "TRADING", priceTick: "0.1", qtyStep: "0.0001", minQty: "0.0001",
  maxQty: "100", minNotional: "5", baseDecimals: 8, quoteDecimals: 2, marketOrdersEnabled: true, stopOrdersEnabled: true, depthGroupings: [],
  paused: false,
} as MarketSymbol;
type Call = [string, { method?: string; account?: string; body?: unknown }?];
const calls = (path: string, method = "GET") => (bff.api.mock.calls as Call[]).filter(([p, o]) => p === path && (o?.method ?? "GET") === method);
const form = (props: Partial<React.ComponentProps<typeof TradeForm>>) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><TradeForm market={market} pickedPrice={null} bestBid="99" bestAsk="100" {...props} /></QueryClientProvider>);
};

beforeEach(() => {
  bff.api.mockImplementation(async (path: string, options?: { method?: string; account?: string }) => {
    if (path === "/bff/wallet/balances") return [{ asset: "USDT", available: options?.account === "lead" ? "2000" : "700", locked: "0", total: "0", version: 1 }];
    if (path === "/bff/fees") return { makerBps: 10, takerBps: 10, makerPercent: "0.1", takerPercent: "0.1", tierLevel: 0, tierName: null, custom: false };
    if (path === "/bff/kyc") return { status: "APPROVED", level: "BASIC", submittedAt: null, reviewedAt: null, expiresAt: null, rejectionReason: null };
    if (path === "/bff/mfa") return { enabled: true, recoveryCodesRemaining: 8, enabledAt: null };
    return { orderId: "O1" };
  });
});
afterEach(() => vi.clearAllMocks());

describe("TradeForm, trading as an account", () => {
  it("offers no account to choose to a customer who has only their own", async () => {
    form({});
    expect(await screen.findByText("700.00 USDT")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Account" })).toBeNull();
    expect(calls("/bff/wallet/balances")[0][1]?.account).toBeUndefined();
  });

  it("a leader chooses between their own account and the lead account", async () => {
    const user = userEvent.setup();
    const onAccount = vi.fn();
    form({ onAccount });
    await user.selectOptions(await screen.findByRole("combobox", { name: "Account" }), "Lead account");
    expect(onAccount).toHaveBeenCalledWith("lead");
  });

  it("as the lead account it shows that account's balance and places the order as it", async () => {
    const user = userEvent.setup();
    form({ account: "lead", onAccount: vi.fn() });
    expect(await screen.findByText("2,000.00 USDT")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Account" })).toHaveValue("lead");

    await user.type(screen.getByPlaceholderText("0.0"), "100");
    await user.type(screen.getByPlaceholderText(/^Min/), "1");
    await user.click(screen.getByRole("button", { name: "Buy BTC" }));
    await waitFor(() => expect(calls("/bff/orders", "POST")).toHaveLength(1));
    const [, sent] = calls("/bff/orders", "POST")[0];
    expect(sent?.account).toBe("lead");
    expect(sent?.body).toMatchObject({ symbol: "BTCUSDT", side: "BUY" });
  });

  it("a paused market shows why and offers no buy or sell", async () => {
    form({ market: { ...market, paused: true } });
    expect(await screen.findByRole("alert")).toHaveTextContent("Trading on BTC/USDT is paused. Open orders can still be cancelled.");
    expect(screen.queryByRole("button", { name: "Buy BTC" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Sell BTC" })).toBeNull();
  });
});
