import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({
  fetchUsers: vi.fn(), fetchUser: vi.fn(), freezeUser: vi.fn(), unfreezeUser: vi.fn(),
  revokeSessions: vi.fn(), revokeApiKey: vi.fn(), suspendTrading: vi.fn(), resumeTrading: vi.fn(),
}));
const session = vi.hoisted(() => ({ role: "OPERATOR" }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  usePathname: () => "/users",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
const records = vi.hoisted(() => ({ fetchOrders: vi.fn(), fetchTrades: vi.fn(), fetchLedger: vi.fn() }));
vi.mock("../orders/api", async (orig) => ({ ...(await orig<object>()), fetchOrders: records.fetchOrders }));
vi.mock("../trades/api", async (orig) => ({ ...(await orig<object>()), fetchTrades: records.fetchTrades }));
vi.mock("../ledger/api", async (orig) => ({ ...(await orig<object>()), fetchLedger: records.fetchLedger }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
const { Users } = await import("./Users");
const { UserDetail } = await import("./UserDetail");

const user = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 7, email: "ann@x.dev", name: null, type: "PRIMARY", status: "ACTIVE", frozenReason: null, frozenWithMaster: false,
  tradingSuspendedAt: null, tradingSuspendedReason: null,
  kycLevel: "BASIC", kycVerifiedUntil: null, mfaEnabled: true, emailVerified: true, createdAt: "2026-09-01T10:00:00Z", ...over,
});
const detail = (over: Partial<Record<string, unknown>> = {}) => ({
  user: user(), master: null,
  subAccounts: [user({ id: 8, email: "a_virtual@x.dev", name: "arb bot", type: "SUB" }),
                user({ id: 9, email: "b_virtual@x.dev", name: "grid bot", type: "SUB", status: "SUSPENDED", frozenWithMaster: true })],
  balances: [{ asset: "USDT", displayDecimals: 2, available: "100", locked: "5", total: "105" }],
  apiKeys: [{ id: "01a0cf7b-0000-7000-8000-000000000003", userId: 8, apiKey: "ak_abcdefgh1234", label: "bot key", canRead: true, canTrade: true,
              lastUsedAt: null, expiresAt: null, createdAt: "2026-09-02T00:00:00Z", revokedAt: null }],
  sessions: [{ id: 5, ipAddress: "10.0.0.1", userAgent: "Firefox", countryCode: "KR",
               createdAt: "2026-09-03T00:00:00Z", lastSeenAt: null, expiresAt: "2026-09-04T00:00:00Z" }],
  ...over,
});

beforeEach(() => {
  nav.query = "";
  session.role = "OPERATOR";
  api.fetchUsers.mockResolvedValue({ items: [user()], total: 1, page: 0, size: 25 });
  api.fetchUser.mockResolvedValue(detail());
});
afterEach(() => vi.clearAllMocks());

describe("Customers list", () => {
  it("searches on the server and keeps the search in the URL", async () => {
    const u = userEvent.setup();
    render(<Users />);
    expect(await screen.findByText("ann@x.dev")).toBeInTheDocument();
    expect(api.fetchUsers).toHaveBeenCalledWith("", "", 0, 25);

    await u.type(screen.getByRole("textbox", { name: /Search/ }), "ann");
    await u.click(screen.getByRole("button", { name: "Search" }));
    expect(nav.replace).toHaveBeenCalledWith("/users?q=ann");
  });

  it("keeps its columns with no match", async () => {
    nav.query = "q=zzz";
    api.fetchUsers.mockResolvedValue({ items: [], total: 0, page: 0, size: 25 });
    render(<Users />);
    expect(await screen.findByText("No customer matches.")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "KYC" })).toBeInTheDocument();
  });
});

describe("Customer detail", () => {
  it("shows the subs under their master, a sub frozen with it named as such", async () => {
    const u = userEvent.setup();
    render(<UserDetail id={7} />);
    await screen.findByText("ann@x.dev");

    await u.click(screen.getByRole("tab", { name: /Sub-accounts/ }));
    const grid = screen.getByText("grid bot").closest("tr")!;
    expect(within(grid).getByText("Frozen with master")).toBeInTheDocument();
  });

  it("freezes with a reason, and says what the server did", async () => {
    const u = userEvent.setup();
    api.freezeUser.mockResolvedValue({ user: user({ status: "SUSPENDED" }), subsAffected: [8], sessionsRevoked: 1 });
    render(<UserDetail id={7} />);
    await screen.findByText("ann@x.dev");

    await u.click(screen.getByRole("button", { name: "Freeze" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("button", { name: "Freeze" })).toBeDisabled(); // no reason yet
    await u.type(within(dialog).getByRole("textbox"), "fraud review");
    await u.click(within(dialog).getByRole("button", { name: "Freeze" }));

    expect(api.freezeUser).toHaveBeenCalledWith(7, "fraud review");
    expect(await screen.findByRole("status")).toHaveTextContent("1 session(s) ended, 1 sub-account(s) frozen with it");
    expect(api.fetchUser).toHaveBeenCalledTimes(2); // refetched, not assumed
  });

  it("revokes a sub's key against the sub that owns it", async () => {
    const u = userEvent.setup();
    api.revokeApiKey.mockResolvedValue({ revoked: true });
    render(<UserDetail id={7} />);
    await screen.findByText("ann@x.dev");

    await u.click(screen.getByRole("tab", { name: /API keys/ }));
    await u.click(screen.getByRole("button", { name: "Revoke" }));
    await u.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Revoke" }));
    expect(api.revokeApiKey).toHaveBeenCalledWith(7, "01a0cf7b-0000-7000-8000-000000000003", 8);
  });

  it("offers a VIEWER nothing to press", async () => {
    session.role = "VIEWER";
    render(<UserDetail id={7} />);
    await screen.findByText("ann@x.dev");
    expect(screen.queryByRole("button", { name: "Freeze" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Log out everywhere" })).toBeNull();
  });

  it("opens that customer's open orders, order history, trades and ledger in tabs, not a link away", async () => {
    const u = userEvent.setup();
    records.fetchOrders.mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
    records.fetchTrades.mockResolvedValue({ items: [], page: 0, size: 20, more: false });
    records.fetchLedger.mockResolvedValue({ items: [], page: 0, size: 20, more: false });
    render(<UserDetail id={7} />);
    await screen.findByText("ann@x.dev");
    expect(screen.queryByRole("link", { name: "Orders" })).not.toBeInTheDocument();

    await u.click(screen.getByRole("tab", { name: "Open orders" }));
    expect(await screen.findByText("No open orders.")).toBeInTheDocument();
    expect(records.fetchOrders).toHaveBeenLastCalledWith(expect.objectContaining({ customer: "7", state: "open" }), 0, 20);

    await u.click(screen.getByRole("tab", { name: "Order history" }));
    expect(await screen.findByText("No finished orders.")).toBeInTheDocument();
    expect(records.fetchOrders).toHaveBeenLastCalledWith(expect.objectContaining({ customer: "7", state: "closed" }), 0, 20);

    await u.click(screen.getByRole("tab", { name: "Trades" }));
    expect(await screen.findByText("No trades.")).toBeInTheDocument();
    await u.click(screen.getByRole("tab", { name: "Ledger" }));
    expect(await screen.findByText("Nothing has moved.")).toBeInTheDocument();
    expect(records.fetchLedger).toHaveBeenCalledWith(expect.objectContaining({ customer: "7" }), 0, 20);
  });

  it("suspends trading with a reason, saying what still works and that orders stay", async () => {
    const u = userEvent.setup();
    api.suspendTrading.mockResolvedValue(user({ tradingSuspendedAt: "2026-09-24T00:00:00Z" }));
    render(<UserDetail id={7} />);
    await u.click(await screen.findByRole("button", { name: "Suspend trading" }));

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("cancel orders");
    expect(dialog).toHaveTextContent("Orders already on the book stay there");
    expect(dialog).toHaveTextContent("2 sub-account(s) are stopped with it");
    await u.type(within(dialog).getByRole("textbox", { name: "Reason" }), "wash trading review");
    await u.click(within(dialog).getByRole("button", { name: "Suspend trading" }));
    expect(api.suspendTrading).toHaveBeenCalledWith(7, "wash trading review");
    expect(await screen.findByRole("status")).toHaveTextContent("Trading suspended.");
  });

  it("shows a suspension on the master, and on its subs as with the master; offers resume", async () => {
    api.fetchUser.mockResolvedValue(detail({
      user: user({ tradingSuspendedAt: "2026-09-24T00:00:00Z", tradingSuspendedReason: "wash trading review" }),
    }));
    const u = userEvent.setup();
    render(<UserDetail id={7} />);
    expect(await screen.findByText("wash trading review")).toBeInTheDocument();
    expect(screen.getByText("Trading suspended")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resume trading" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Suspend trading" })).not.toBeInTheDocument();

    await u.click(screen.getByRole("tab", { name: /Sub-accounts/ }));
    const arb = screen.getByText("arb bot").closest("tr")!;
    expect(within(arb).getByText("Trading suspended with master")).toBeInTheDocument();
  });
});
