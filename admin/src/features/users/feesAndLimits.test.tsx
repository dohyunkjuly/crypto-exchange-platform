import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  fetchOverrides: vi.fn(), setFee: vi.fn(), removeFee: vi.fn(), setOrderLimit: vi.fn(), removeOrderLimit: vi.fn(),
  setMarketLimit: vi.fn(), removeMarketLimit: vi.fn(),
}));
const markets = vi.hoisted(() => ({ fetchMarkets: vi.fn() }));
vi.mock("./overridesApi", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("../markets/api", async (orig) => ({ ...(await orig<object>()), ...markets }));
const { FeesAndLimits } = await import("./FeesAndLimits");

const grant = (over: Partial<Record<string, unknown>> = {}) => ({
  reason: "market maker agreement", expiresAt: null, effective: true, grantedBy: "ops@x.dev", updatedAt: "2026-09-01T00:00:00Z", ...over,
});
const overrides = (over: Partial<Record<string, unknown>> = {}) => ({
  fee: { makerFeeBps: 10, takerFeeBps: 10, source: "TIER", tierLevel: 0, tierName: "VIP 0", subAccount: false, override: null },
  orderLimit: { symbol: null, defaultOrders: 1000, defaultStops: 200, own: null },
  marketLimits: [],
  ...over,
});
const code = "123456";

beforeEach(() => {
  api.fetchOverrides.mockResolvedValue(overrides());
  markets.fetchMarkets.mockResolvedValue({ markets: [{ id: 1, symbol: "BTCUSDT" }, { id: 2, symbol: "ETHUSDT" }], assets: [], partitionCount: 8 });
});
afterEach(() => vi.clearAllMocks());

describe("Fees & limits", () => {
  it("says where the fee comes from, and sets a master's override with a code", async () => {
    const u = userEvent.setup();
    api.setFee.mockResolvedValue(overrides({ fee: { makerFeeBps: -1, takerFeeBps: 4, source: "OVERRIDE", tierLevel: null,
      tierName: null, subAccount: false, override: { makerFeeBps: -1, takerFeeBps: 4, ...grant() } } }));
    render(<FeesAndLimits userId={7} masterId={null} canAct />);
    expect(await screen.findByText("From VIP 0")).toBeInTheDocument();

    await u.click(screen.getByRole("button", { name: "Set override" }));
    const dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByRole("textbox", { name: "Maker fee" }), "-1");
    await u.type(within(dialog).getByRole("textbox", { name: "Taker fee" }), "4");
    await u.type(within(dialog).getByRole("textbox", { name: "Reason" }), "market maker agreement");
    await u.type(within(dialog).getByRole("textbox", { name: /authenticator/i }), code);
    await u.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(api.setFee).toHaveBeenCalledWith(7, { makerFeeBps: -1, takerFeeBps: 4, reason: "market maker agreement", expiresAt: null }, code);
    expect(await screen.findByText("From their override")).toBeInTheDocument();
  });

  it("shows a sub its master's fee, with no way to set one of its own", async () => {
    api.fetchOverrides.mockResolvedValue(overrides({ fee: { makerFeeBps: 2, takerFeeBps: 5, source: "OVERRIDE",
      tierLevel: null, tierName: null, subAccount: true, override: { makerFeeBps: 2, takerFeeBps: 5, ...grant() } } }));
    render(<FeesAndLimits userId={8} masterId={7} canAct />);
    expect(await screen.findByText(/From their master's override/)).toBeInTheDocument();
    expect(screen.getByText(/Master's override: maker 2/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "open master" })).toHaveAttribute("href", "/users/7");
    expect(screen.queryByRole("button", { name: /override/ })).not.toBeInTheDocument();
  });

  it("shows each cap against its default, and adds a market limit leaving a blank cap at the default", async () => {
    const u = userEvent.setup();
    api.fetchOverrides.mockResolvedValue(overrides({
      marketLimits: [{ symbol: "ETHUSDT", defaultOrders: 200, defaultStops: 50, own: { maxOrders: 500, maxStops: null, ...grant({ reason: "grid" }) } }],
    }));
    api.setMarketLimit.mockResolvedValue(overrides());
    render(<FeesAndLimits userId={8} masterId={7} canAct />);

    const eth = (await screen.findByText("ETHUSDT")).closest("tr")!;
    expect(within(eth).getByText("500")).toBeInTheDocument();
    expect(within(eth).getByText("50")).toBeInTheDocument();
    expect(within(screen.getByText("All markets").closest("tr")!).getByText("1,000")).toBeInTheDocument();

    await u.click(screen.getByRole("button", { name: "Add market limit" }));
    const dialog = screen.getByRole("dialog");
    const market = within(dialog).getByRole("combobox", { name: "Market" });
    await screen.findByRole("option", { name: "BTCUSDT" });
    expect(within(market).queryByRole("option", { name: "ETHUSDT" })).not.toBeInTheDocument();
    await u.selectOptions(market, "BTCUSDT");
    await u.type(within(dialog).getByRole("textbox", { name: "Open orders" }), "800");
    await u.type(within(dialog).getByRole("textbox", { name: "Reason" }), "grid on BTC");
    await u.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(api.setMarketLimit).toHaveBeenCalledWith(8, { symbol: "BTCUSDT", maxOrders: 800, maxStops: null, reason: "grid on BTC", expiresAt: null });
  });

  it("offers a VIEWER nothing to change", async () => {
    render(<FeesAndLimits userId={7} masterId={null} canAct={false} />);
    await screen.findByText("From VIP 0");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
