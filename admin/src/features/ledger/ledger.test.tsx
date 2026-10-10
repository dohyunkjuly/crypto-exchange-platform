import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchLedger: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  usePathname: () => "/ledger",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { Ledger } = await import("./Ledger");
const { CustomerLedger } = await import("./CustomerLedger");

const entry = (over: Partial<Record<string, unknown>> = {}) => ({
  id: "e1", userId: 7, userEmail: "ann@x.dev", asset: "BTC", displayDecimals: 2, type: "FEE",
  amount: "-0.00012345", balanceAfter: "1.23456789", referenceType: "ORDER",
  referenceId: "0190aaaa-0000-7000-8000-000000000001", createdAt: "2026-09-20T10:00:00Z", ...over,
});

beforeEach(() => {
  nav.query = "";
  api.fetchLedger.mockResolvedValue({
    items: [entry(), entry({ id: "e2", type: "DEPOSIT", amount: "100", balanceAfter: "100", referenceType: "DEPOSIT", referenceId: "42" })],
    page: 0, size: 50, more: false,
  });
});
afterEach(() => vi.clearAllMocks());

describe("Ledger", () => {
  it("shows every digit, signed, never rounded to the asset's display places", async () => {
    render(<Ledger />);
    const fee = (await screen.findByRole("cell", { name: "Fee" })).closest("tr")!;
    expect(within(fee).getByText("-0.00012345")).toBeInTheDocument();
    expect(within(fee).getByText("1.23456789")).toBeInTheDocument();
    expect(within(fee).getByRole("link", { name: "Order 0190aaaa" })).toHaveAttribute("href", "/orders/0190aaaa-0000-7000-8000-000000000001");

    const deposit = screen.getByRole("cell", { name: "Deposit" }).closest("tr")!;
    expect(within(deposit).getByText("+100")).toBeInTheDocument();
    expect(within(deposit).getByText("Deposit 42")).toBeInTheDocument();
  });

  it("filters by movement from the URL, and changing it goes to the first page", async () => {
    const u = userEvent.setup();
    nav.query = "customer=fees%40exchange&page=2";
    render(<Ledger />);
    await screen.findByRole("cell", { name: "Fee" });
    expect(api.fetchLedger).toHaveBeenCalledWith({ customer: "fees@exchange", asset: "", type: "", from: "", to: "" }, 2, 50);
    await u.selectOptions(screen.getByRole("combobox", { name: "Movement" }), "FEE_SWEEP");
    expect(nav.replace).toHaveBeenCalledWith("/ledger?customer=fees%40exchange&type=FEE_SWEEP");
  });
});

describe("A customer's ledger", () => {
  it("is theirs alone, so it has no customer column", async () => {
    render(<CustomerLedger userId={7} />);
    await screen.findByRole("cell", { name: "Fee" });
    expect(screen.queryByRole("columnheader", { name: "Customer" })).not.toBeInTheDocument();
    expect(api.fetchLedger).toHaveBeenCalledWith({ customer: "7", asset: "", type: "", from: "", to: "" }, 0, 20);
  });
});
