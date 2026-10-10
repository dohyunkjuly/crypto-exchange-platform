import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchSubAccounts: vi.fn(), fetchTransfers: vi.fn() }));
const session = vi.hoisted(() => ({ useSessionQuery: () => ({ data: { id: 1, email: "ann@x.dev" } }) }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/auth/session", () => session);
const { SubTransfers } = await import("./TransferHistory");
const { matchesTransfer } = await import("./TransferTable");

const MASTER = 1;
const tx = (from: number, to: number, asset = "USDT") => ({
  transferId: `${from}-${to}-${asset}`, fromUserId: from, toUserId: to, asset, amount: "250",
  createdAt: "2026-09-02T10:00:00Z",
});

const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  api.fetchSubAccounts.mockResolvedValue([{ id: 2, email: "e", label: "arb bot", frozen: false, createdAt: "2026-09-01T00:00:00Z" }]);
  api.fetchTransfers.mockResolvedValue([]);
});
afterEach(() => vi.clearAllMocks());

describe("matchesTransfer", () => {
  it("takes a sub on either side of the transfer", () => {
    expect(matchesTransfer(tx(MASTER, 2), { sub: "2", asset: "", direction: "" }, MASTER)).toBe(true);
    expect(matchesTransfer(tx(2, MASTER), { sub: "2", asset: "", direction: "" }, MASTER)).toBe(true);
    expect(matchesTransfer(tx(MASTER, 3), { sub: "2", asset: "", direction: "" }, MASTER)).toBe(false);
  });

  it("reads direction against the sub the filter names", () => {
    const into2 = { sub: "2", asset: "", direction: "in" } as const;
    expect(matchesTransfer(tx(MASTER, 2), into2, MASTER)).toBe(true);
    expect(matchesTransfer(tx(2, MASTER), into2, MASTER)).toBe(false);
    // Sub to sub: into 2 is out of 3, and each answers for itself.
    expect(matchesTransfer(tx(3, 2), into2, MASTER)).toBe(true);
    expect(matchesTransfer(tx(3, 2), { sub: "3", asset: "", direction: "in" }, MASTER)).toBe(false);
  });

  it("with no sub named, direction is the master's own", () => {
    expect(matchesTransfer(tx(MASTER, 2), { sub: "", asset: "", direction: "out" }, MASTER)).toBe(true);
    expect(matchesTransfer(tx(MASTER, 2), { sub: "", asset: "", direction: "in" }, MASTER)).toBe(false);
    // Between two subs: neither direction is the master's, so it is in neither answer.
    expect(matchesTransfer(tx(2, 3), { sub: "", asset: "", direction: "in" }, MASTER)).toBe(false);
    expect(matchesTransfer(tx(2, 3), { sub: "", asset: "", direction: "out" }, MASTER)).toBe(false);
  });

  it("narrows to one asset", () => {
    expect(matchesTransfer(tx(MASTER, 2, "BTC"), { sub: "", asset: "BTC", direction: "" }, MASTER)).toBe(true);
    expect(matchesTransfer(tx(MASTER, 2, "USDT"), { sub: "", asset: "BTC", direction: "" }, MASTER)).toBe(false);
  });
});

describe("Sub-account transfer history", () => {
  it("with no transfers and no subs, keeps its columns and its one action", async () => {
    api.fetchSubAccounts.mockResolvedValue([]);
    withClient(<SubTransfers />);

    expect(await screen.findByRole("columnheader", { name: "From" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Amount" })).toBeInTheDocument();
    expect(screen.getByText("No transfers yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Transfer" })).toHaveAttribute("href", "/en/my/sub-accounts/transfer");
  });

  it("filters on Search, not as the selects change", async () => {
    const user = userEvent.setup();
    api.fetchTransfers.mockResolvedValue([tx(MASTER, 2, "USDT"), tx(MASTER, 2, "BTC")]);
    withClient(<SubTransfers />);

    await screen.findByText("250 USDT");
    await user.selectOptions(screen.getByRole("combobox", { name: "Asset" }), "BTC");
    expect(screen.getByText("250 USDT")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(screen.queryByText("250 USDT")).toBeNull();
    expect(screen.getByText("250 BTC")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Reset" }));
    expect(screen.getByText("250 USDT")).toBeInTheDocument();
  });

  it("names both sides rather than numbering them", async () => {
    api.fetchTransfers.mockResolvedValue([
      { transferId: "t1", fromUserId: 1, toUserId: 2, asset: "USDT", amount: "250", createdAt: "2026-09-02T10:00:00Z" },
    ]);
    withClient(<SubTransfers />);

    const row = (await screen.findByText("250 USDT")).closest("tr")!;
    expect(within(row).getByText("Master account")).toBeInTheDocument();
    expect(within(row).getByText("arb bot")).toBeInTheDocument();
    expect(screen.queryByText("No transfers yet")).toBeNull();
  });
});
