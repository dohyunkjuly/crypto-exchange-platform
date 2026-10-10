import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchMarkets: vi.fn(), fetchMarket: vi.fn(), listMarket: vi.fn(), setStatus: vi.fn(), setLimits: vi.fn() }));
const session = vi.hoisted(() => ({ role: "OPERATOR" }));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
const { Markets } = await import("./Markets");
const { MarketDetail } = await import("./MarketDetail");

const limits = {
  minQty: "0.001", maxQty: "1000", minNotional: "5", maxNotional: null, marketMinQty: null, marketMaxQty: null,
  minQuoteQty: null, maxQuoteQty: null, priceBandBps: null, maxOpenOrders: 200, maxOpenConditionalOrders: 50,
  marketOrdersEnabled: true, stopOrdersEnabled: false,
};
const market = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 1, symbol: "BTCUSDT", base: "BTC", quote: "USDT", status: "TRADING", commandPartition: 0,
  priceTick: "0.01", qtyStep: "0.00001", limits, createdAt: "2026-09-01T00:00:00Z", ...over,
});
const code = "123456";

beforeEach(() => {
  session.role = "OPERATOR";
  api.fetchMarkets.mockResolvedValue({
    markets: [market(), market({ id: 2, symbol: "SOLUSDT", base: "SOL", status: "CANCEL_ONLY", commandPartition: 2 })],
    assets: [{ id: 10, symbol: "SOL", name: "Solana" }, { id: 11, symbol: "USDT", name: "Tether" }, { id: 12, symbol: "DOGE", name: "Dogecoin" }],
    partitionCount: 4,
  });
});
afterEach(() => vi.clearAllMocks());

describe("Markets", () => {
  it("lists every market and filters by status", async () => {
    const u = userEvent.setup();
    render(<Markets />);
    expect(await screen.findByRole("link", { name: "SOLUSDT" })).toBeInTheDocument();
    await u.selectOptions(screen.getByRole("combobox", { name: "Status" }), "CANCEL_ONLY");
    expect(screen.queryByRole("link", { name: "BTCUSDT" })).not.toBeInTheDocument();
  });

  it("lists a market closed, showing which markets share each partition, and sends the code", async () => {
    const u = userEvent.setup();
    api.listMarket.mockResolvedValue(market({ id: 3, symbol: "DOGEUSDT", status: "PRE_TRADING" }));
    render(<Markets />);
    await u.click(await screen.findByRole("button", { name: "List a market" }));
    const dialog = screen.getByRole("dialog");
    await u.selectOptions(within(dialog).getByRole("combobox", { name: "Base asset" }), "12");
    await u.selectOptions(within(dialog).getByRole("combobox", { name: "Quote asset" }), "11");
    expect(dialog).toHaveTextContent("Listed as DOGEUSDT");
    const partition = within(dialog).getByRole("combobox", { name: "Engine partition" });
    expect(within(partition).getByRole("option", { name: "2 · SOLUSDT" })).toBeInTheDocument();
    expect(within(partition).getByRole("option", { name: "3 · free" })).toBeInTheDocument();
    await u.selectOptions(partition, "3");
    for (const [name, value] of [["Price tick", "0.00001"], ["Quantity step", "1"], ["Min quantity", "10"], ["Max quantity", "1000000"], ["Min order value", "1"]]) {
      await u.type(within(dialog).getByRole("textbox", { name }), value);
    }
    const list = within(dialog).getByRole("button", { name: "List" });
    expect(list).toBeDisabled();
    await u.type(within(dialog).getByRole("textbox", { name: /authenticator/i }), code);
    await u.click(list);

    expect(api.listMarket).toHaveBeenCalledWith(expect.objectContaining({
      baseAssetId: 12, quoteAssetId: 11, commandPartition: 3, priceTick: "0.00001", qtyStep: "1",
      limits: expect.objectContaining({ minQty: "10", maxQty: "1000000", minNotional: "1", maxOpenOrders: 200 }),
    }), code);
    expect(await screen.findByRole("status")).toHaveTextContent("DOGEUSDT listed");
  });

  it("offers a VIEWER no listing", async () => {
    session.role = "VIEWER";
    render(<Markets />);
    await screen.findByRole("link", { name: "BTCUSDT" });
    expect(screen.queryByRole("button", { name: "List a market" })).not.toBeInTheDocument();
  });
});

describe("Market", () => {
  it.each([
    ["PRE_TRADING", "Open market", "TRADING"],
    ["TRADING", "Set cancel-only", "CANCEL_ONLY"],
    ["CANCEL_ONLY", "Resume trading", "TRADING"],
  ])("a %s market offers %s, with a code", async (status, label, to) => {
    const u = userEvent.setup();
    api.fetchMarket.mockResolvedValue(market({ status }));
    api.setStatus.mockResolvedValue(market({ status: to }));
    render(<MarketDetail id="1" />);
    await u.click(await screen.findByRole("button", { name: label }));
    const dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByRole("textbox", { name: /authenticator/i }), code);
    await u.click(within(dialog).getByRole("button", { name: label }));
    expect(api.setStatus).toHaveBeenCalledWith(1, to, code);
  });

  it("shows the tick, step and partition as facts, never as fields", async () => {
    api.fetchMarket.mockResolvedValue(market());
    render(<MarketDetail id="1" />);
    expect(await screen.findByText("0.00001")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /tick|step|partition/i })).not.toBeInTheDocument();
  });

  it("saves changed limits with a code, a blank optional limit as none", async () => {
    const u = userEvent.setup();
    api.fetchMarket.mockResolvedValue(market({ limits: { ...limits, maxNotional: "100000" } }));
    api.setLimits.mockResolvedValue(market());
    render(<MarketDetail id="1" />);
    const save = await screen.findByRole("button", { name: "Save limits" });
    expect(save).toBeDisabled();

    await u.clear(screen.getByRole("textbox", { name: "Max order value" }));
    await u.type(screen.getByRole("textbox", { name: "Price band (bps from last price)" }), "500");
    await u.click(screen.getByRole("checkbox", { name: "Stop orders" }));
    await u.click(save);
    const dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByRole("textbox", { name: /authenticator/i }), code);
    await u.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(api.setLimits).toHaveBeenCalledWith(1, expect.objectContaining({ maxNotional: null, priceBandBps: 500, stopOrdersEnabled: true, minQty: "0.001" }), code);
  });

  it("offers a VIEWER nothing to change", async () => {
    session.role = "VIEWER";
    api.fetchMarket.mockResolvedValue(market());
    render(<MarketDetail id="1" />);
    await screen.findByText("BTCUSDT");
    expect(screen.queryByRole("button", { name: /cancel-only|Save limits/ })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Min quantity" })).toBeDisabled();
  });
});
