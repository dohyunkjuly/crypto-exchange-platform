import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), to: null as string | null }));
const api = vi.hoisted(() => ({
  fetchSubAccounts: vi.fn(), setSubAccountFrozen: vi.fn(), fetchTransfers: vi.fn(), transfer: vi.fn(),
  fetchAssets: vi.fn(), fetchBalances: vi.fn(), fetchSubAccountBalances: vi.fn(), createSubAccount: vi.fn(),
}));
const session = vi.hoisted(() => ({ useSessionQuery: () => ({ data: { id: 1, email: "ann@x.dev" } }) }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  useSearchParams: () => new URLSearchParams(nav.to ? `to=${nav.to}` : ""),
}));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/auth/session", () => session);
const { SubAccounts } = await import("./SubAccounts");
const { Transfer } = await import("./Transfer");

const SUBS = [
  { id: 2, email: "ann+sub-a@x.dev", label: "arb bot", frozen: false, createdAt: "2026-09-01T00:00:00Z" },
  { id: 3, email: "ann+sub-b@x.dev", label: "grid bot", frozen: true, createdAt: "2026-09-02T00:00:00Z" },
];
const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  api.fetchSubAccounts.mockResolvedValue(SUBS);
  api.fetchTransfers.mockResolvedValue([]);
  api.fetchAssets.mockResolvedValue([{ symbol: "USDT", name: "Tether", assetType: "STABLE", displayDecimals: 2 }, { symbol: "BTC", name: "Bitcoin", assetType: "COIN", displayDecimals: 5 }]);
  api.fetchBalances.mockResolvedValue([
    { asset: "USDT", available: "100.5", locked: "0", total: "100.5", version: 1 },
    // Held, but all of it is in orders: there is nothing here to send.
    { asset: "BTC", available: "0", locked: "0.5", total: "0.5", version: 1 },
  ]);
  api.fetchSubAccountBalances.mockResolvedValue([{ asset: "USDT", available: "20", locked: "0", total: "20", version: 1 }]);
  api.setSubAccountFrozen.mockImplementation(async ({ id, frozen }: { id: number; frozen: boolean }) => ({ ...SUBS.find((s) => s.id === id)!, frozen }));
  nav.to = null;
});
afterEach(() => vi.clearAllMocks());

describe("Sub-accounts", () => {
  it("lists each sub with its state; freezing asks first, unfreezing does not", async () => {
    const user = userEvent.setup();
    withClient(<SubAccounts />);

    const arb = (await screen.findByRole("cell", { name: "arb bot" })).closest("tr")!;
    expect(within(arb).getByText("Active")).toBeInTheDocument();
    await user.click(within(arb).getByRole("button", { name: "Freeze" }));
    expect(api.setSubAccountFrozen).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.setSubAccountFrozen.mock.calls[0][0]).toEqual({ id: 2, frozen: true }));

    const grid = screen.getByRole("cell", { name: "grid bot" }).closest("tr")!;
    await user.click(within(grid).getByRole("button", { name: "Unfreeze" }));
    await waitFor(() => expect(api.setSubAccountFrozen.mock.calls[1][0]).toEqual({ id: 3, frozen: false }));
  });

  it("with no subs, says so and still offers Create", async () => {
    api.fetchSubAccounts.mockResolvedValue([]);
    withClient(<SubAccounts />);
    expect(await screen.findByText("No sub-accounts yet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Create sub-account" })).toBeEnabled();
  });

  it("filters on Search, not as the selects change, and Reset puts them back", async () => {
    const user = userEvent.setup();
    withClient(<SubAccounts />);
    await screen.findByRole("cell", { name: "arb bot" });

    await user.selectOptions(screen.getByRole("combobox", { name: "Status" }), "frozen");
    expect(screen.getByRole("cell", { name: "arb bot" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(screen.queryByRole("cell", { name: "arb bot" })).toBeNull();
    expect(screen.getByRole("cell", { name: "grid bot" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Reset" }));
    expect(screen.getByRole("cell", { name: "arb bot" })).toBeInTheDocument();
  });

  it("opens the filters as a sheet over the page on a phone, and searching closes it", async () => {
    const user = userEvent.setup();
    withClient(<SubAccounts />);
    const funnel = await screen.findByRole("button", { name: "Filters" });

    expect(funnel).toHaveAttribute("aria-expanded", "false");
    await user.click(funnel);
    expect(funnel).toHaveAttribute("aria-expanded", "true");
    // The same selects, not a second copy of them: one document, one control per label.
    expect(screen.getAllByRole("combobox", { name: "Status" })).toHaveLength(1);

    // Searching closes it: the answer is the list, not the form.
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(funnel).toHaveAttribute("aria-expanded", "false");
  });

  it("creates in a dialog, and the count is every sub, not the filtered ones", async () => {
    const user = userEvent.setup();
    api.createSubAccount.mockResolvedValue({ id: 4, label: "new", email: "e", frozen: false, createdAt: "2026-09-03T00:00:00Z" });
    withClient(<SubAccounts />);
    expect(await screen.findByText("Sub-accounts (2)")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "+ Create sub-account" }));
    const dialog = screen.getByRole("dialog", { name: "Create sub-account" });
    await user.type(within(dialog).getByLabelText("Label"), "new");
    await user.click(within(dialog).getByRole("button", { name: "Create sub-account" }));
    await waitFor(() => expect(api.createSubAccount.mock.calls[0][0]).toEqual({ label: "new" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("pages the list, and a filter that shrinks it lands on a page that exists", async () => {
    const user = userEvent.setup();
    const many = Array.from({ length: 16 }, (_, i) => ({
      id: 100 + i, email: `ann+sub-${i}@x.dev`, label: `bot ${i}`, frozen: i === 15, createdAt: "2026-09-01T00:00:00Z",
    }));
    api.fetchSubAccounts.mockResolvedValue(many);
    withClient(<SubAccounts />);

    await screen.findByRole("cell", { name: "bot 0" });
    expect(screen.getAllByRole("row")).toHaveLength(11); // 10 subs and the header
    expect(screen.queryByRole("cell", { name: "bot 15" })).toBeNull();
    expect(screen.getByText("Page 1 of 2")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("cell", { name: "bot 15" })).toBeInTheDocument();
    expect(screen.queryByRole("cell", { name: "bot 0" })).toBeNull();

    // A filter leaving one page while standing on page two: the match shows, not an empty table.
    await user.selectOptions(screen.getByRole("combobox", { name: "Status" }), "frozen");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(screen.getByRole("cell", { name: "bot 15" })).toBeInTheDocument();
    expect(screen.queryByText("No sub-account matches.")).toBeNull();
  });

  it("with nothing to send, keeps the fields but greys them out", async () => {
    api.fetchBalances.mockResolvedValue([]);
    withClient(<Transfer />);

    expect(await screen.findByText("This account holds nothing to transfer.")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Asset" })).toBeDisabled();
    expect(screen.getByRole("textbox")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Max" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Transfer" })).toBeDisabled();
  });
});

describe("Sub-accounts list", () => {
  it("offers a transfer on an active sub and none on a frozen one", async () => {
    withClient(<SubAccounts />);
    const arb = (await screen.findByRole("cell", { name: "arb bot" })).closest("tr")!;
    const grid = screen.getByRole("cell", { name: "grid bot" }).closest("tr")!;
    expect(within(arb).getByRole("link", { name: "Transfer" })).toHaveAttribute("href", "/en/my/sub-accounts/transfer?to=2");
    expect(within(grid).queryByRole("link", { name: "Transfer" })).toBeNull();
  });
});

describe("Transfer", () => {
  it("offers only unfrozen accounts, never the same one on both sides, and caps at the sender's balance", async () => {
    const user = userEvent.setup();
    nav.to = "2";
    withClient(<Transfer />);

    const from = await screen.findByRole("combobox", { name: "From" });
    const to = screen.getByRole("combobox", { name: "To" });
    expect(within(from).getAllByRole("option").map((o) => o.textContent)).toEqual(["Master account", "arb bot"]);
    expect(within(to).getAllByRole("option").map((o) => o.textContent)).toEqual(["arb bot"]);
    expect(to).toHaveValue("2");
    // Only what the sender can actually send: BTC is all locked, so it is not offered.
    expect(within(screen.getByRole("combobox", { name: "Asset" })).getAllByRole("option").map((o) => o.textContent)).toEqual(["USDT"]);
    expect(await screen.findByText("Available: 100.50 USDT")).toBeInTheDocument();

    await user.type(screen.getByRole("textbox"), "101");
    expect(screen.getByRole("button", { name: "Transfer" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Max" }));
    expect(screen.getByRole("textbox")).toHaveValue("100.5");
    api.transfer.mockResolvedValue({});
    await user.click(screen.getByRole("button", { name: "Transfer" }));

    await waitFor(() => expect(api.transfer).toHaveBeenCalled());
    const sent = api.transfer.mock.calls[0][0];
    expect(sent).toMatchObject({ fromUserId: 1, toUserId: 2, asset: "USDT", amount: "100.5" });
    expect(sent.transferId).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByText("Transferred.")).toBeInTheDocument();
  });
});
