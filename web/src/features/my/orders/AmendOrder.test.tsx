import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MarketSymbol } from "@/features/markets/api";
import type { Order } from "../shared/api";

const api = vi.hoisted(() => ({ amendOrder: vi.fn() }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { AmendOrder } = await import("./AmendOrder");

const m = { symbol: "BTCUSDT", baseAsset: "BTC", quoteAsset: "USDT", priceTick: "0.1", qtyStep: "0.0001", minQty: "0.0001", maxQty: "100", minNotional: "5", quoteDecimals: 2 } as MarketSymbol;
const order = (over: Partial<Order> = {}): Order => ({
  orderId: "a", clientOrderId: null, symbol: "BTCUSDT", side: "BUY", orderType: "LIMIT", timeInForce: "GTC", status: "NEW",
  price: "100", stopPrice: null, trailingDeltaBps: null, triggerDirection: null, quantity: "2", quoteOrderQty: null,
  filledQuantity: "0", remainingQuantity: "2", cumulativeQuoteQuantity: "0", averageFillPrice: null, rejectReason: null,
  createdAt: "t0", updatedAt: "t0", ...over,
});
const renderDialog = (o: Order, onClose = vi.fn()) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(["orders", "open"], [o]);
  render(<QueryClientProvider client={client}><AmendOrder order={o} market={m} onClose={onClose} /></QueryClientProvider>);
  return { client, onClose };
};
afterEach(() => { vi.clearAllMocks(); });

describe("AmendOrder", () => {
  it("sends one PATCH with only the changed field, marks the row pending and closes", async () => {
    const user = userEvent.setup();
    api.amendOrder.mockResolvedValue({ orderId: "a", status: "AMENDING" });
    const { client, onClose } = renderDialog(order());

    expect(screen.getByRole("button", { name: "Update order" })).toBeDisabled();
    await user.clear(screen.getByLabelText("Price"));
    await user.type(screen.getByLabelText("Price"), "101");
    await user.click(screen.getByRole("button", { name: "Update order" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(api.amendOrder).toHaveBeenCalledTimes(1);
    expect(api.amendOrder.mock.calls[0][0]).toEqual({ orderId: "a", price: "101.0" });
    expect(client.getQueryData<Order[]>(["orders", "open"])![0].amendPending).toBe(true);
  });

  it("offers a trailing order its size and nothing else", () => {
    renderDialog(order({ status: "PENDING_TRIGGER", orderType: "STOP_LOSS", price: null, stopPrice: "90", trailingDeltaBps: 100 }));

    expect(screen.queryByLabelText("Price")).toBeNull();
    expect(screen.queryByLabelText("Trigger")).toBeNull();
    expect(screen.getByLabelText("Amount (BTC)")).toBeInTheDocument();
  });

  it("a failed PATCH is shown, never resent, and the dialog stays open", async () => {
    const user = userEvent.setup();
    const { BffError } = await import("@/lib/bff/errors");
    api.amendOrder.mockRejectedValue(new BffError(5026, 409, "pending"));
    const { onClose } = renderDialog(order());

    await user.clear(screen.getByLabelText("Price"));
    await user.type(screen.getByLabelText("Price"), "101");
    await user.click(screen.getByRole("button", { name: "Update order" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("An edit of this order is still being processed.");
    expect(api.amendOrder).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });
});
