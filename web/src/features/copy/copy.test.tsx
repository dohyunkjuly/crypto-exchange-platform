import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn() }));
const api = vi.hoisted(() => ({
  fetchLeaders: vi.fn(), fetchLeader: vi.fn(), fetchFollows: vi.fn(), fetchFollow: vi.fn(), fetchCopies: vi.fn(),
  fetchShares: vi.fn(), fetchLeaderTrades: vi.fn(),
  follow: vi.fn(), addFunds: vi.fn(), removeFunds: vi.fn(), stopCopying: vi.fn(),
  fetchLead: vi.fn(), applyToLead: vi.fn(), fundLead: vi.fn(), withdrawLead: vi.fn(), stopLeading: vi.fn(),
  fetchLeadApiKeys: vi.fn(), createLeadApiKey: vi.fn(), revokeLeadApiKey: vi.fn(), setPortfolioShown: vi.fn(),
}));
const my = vi.hoisted(() => ({ fetchBalances: vi.fn(), fetchKyc: vi.fn(), fetchMfa: vi.fn() }));
const markets = vi.hoisted(() => ({ fetchSymbols: vi.fn() }));
const session = vi.hoisted(() => ({ status: "signedIn" as "signedIn" | "signedOut" }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: nav.replace }), usePathname: () => "/en/copy" }));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/my/shared/api", async (orig) => ({ ...(await orig<object>()), ...my }));
vi.mock("@/features/markets/api", async (orig) => ({ ...(await orig<object>()), ...markets }));
vi.mock("@/features/auth/session", () => ({ useSession: () => ({ status: session.status, user: { id: 1 } }) }));
const { Leaders } = await import("./Leaders");
const { LeaderProfile } = await import("./LeaderProfile");
const { MyCopies } = await import("./MyCopies");
const { CopyAccount } = await import("./CopyAccount");
const { Lead } = await import("./Lead");

const TERMS = { quoteAsset: "USDT", minFixedRatioAmount: "100", minFixedAmount: "10", profitShareBps: 1000, maxCopies: 10, maxFollowers: 300, failureLimit: 5, leadMinBalance: "500" };
const MINA = { id: "L1", displayName: "Mina", bio: "trades the majors", followers: 12, accepting: true, profitShareBps: 1500, since: "2026-09-01T00:00:00Z", portfolioShown: true,
  stats: { days7: { return: "1.2", pnl: "80", maxDrawdown: "0.5" }, days30: { return: "12.34", pnl: "1250.5", maxDrawdown: "4.2" },
    days90: { return: null, pnl: null, maxDrawdown: null }, copySuccessRate: "97.5", fundsCopying: "25000",
    series: [{ day: "2026-09-29", value: "0" }, { day: "2026-09-30", value: "3" }, { day: "2026-10-01", value: "-1" }, { day: "2026-10-02", value: "12.34" }], asOf: "2026-10-02T00:30:00Z" } };
const FULL = { id: "L2", displayName: "Joon", bio: null, followers: 300, accepting: false, profitShareBps: 1000, since: "2026-08-01T00:00:00Z", portfolioShown: true, stats: null };
const PROFILE = { leader: MINA, terms: TERMS, leadingBalance: "12500.5",
  portfolio: { holdings: [{ asset: "USDT", amount: "9000", value: "9000" }, { asset: "BTC", amount: "0.05", value: "3000" }] } };
const TRADE = { time: "2026-10-02T09:30:00Z", symbol: "BTCUSDT", side: "BUY", price: "60000", quantity: "0.05", quoteQuantity: "3000" };
const FOLLOW = {
  id: "F1", leaderId: "L1", leaderName: "Mina", mode: "FIXED_RATIO", amountPerOrder: null, slippageCapBps: 50, status: "ACTIVE",
  closeReason: null, failuresInRow: 2, profitShareBps: 1000, realizedProfit: "48.5", sharedProfit: "4.85", value: "1000",
  createdAt: "2026-09-10T00:00:00Z", closedAt: null,
  holdings: [{ asset: "USDT", available: "400", locked: "0" }, { asset: "BTC", available: "0.01", locked: "0" }],
};
const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  session.status = "signedIn";
  api.fetchLeaders.mockResolvedValue({ leaders: [MINA, FULL], terms: TERMS });
  api.fetchLeader.mockResolvedValue(PROFILE);
  api.fetchFollows.mockResolvedValue([FOLLOW]);
  api.fetchFollow.mockResolvedValue({ follow: FOLLOW });
  api.fetchShares.mockResolvedValue([
    { id: "S1", trigger: "WEEKLY", due: "4.85", paid: "4.85", createdAt: "2026-09-14T00:05:00Z" },
  ]);
  api.fetchLeaderTrades.mockResolvedValue([TRADE]);
  api.fetchCopies.mockResolvedValue([
    { id: "C1", symbol: "BTCUSDT", side: "BUY", status: "FILLED", reason: null, orderId: "O1", closing: false, createdAt: "2026-09-11T10:00:00Z",
      filled: "0.002", averagePrice: "63250.5", total: "126.501" },
    { id: "C2", symbol: "ETHUSDT", side: "SELL", status: "SKIPPED", reason: "NOTHING_TO_SELL", orderId: null, closing: false, createdAt: "2026-09-11T11:00:00Z",
      filled: null, averagePrice: null, total: null },
  ]);
  api.follow.mockResolvedValue({ ...FOLLOW, id: "new-follow" });
  api.addFunds.mockResolvedValue(undefined);
  api.removeFunds.mockResolvedValue(undefined);
  api.stopCopying.mockResolvedValue(undefined);
  api.applyToLead.mockResolvedValue({});
  api.fundLead.mockResolvedValue(undefined);
  api.withdrawLead.mockResolvedValue(undefined);
  api.stopLeading.mockResolvedValue({});
  my.fetchBalances.mockResolvedValue([{ asset: "USDT", available: "1000", locked: "0", total: "1000", version: 1 }]);
  my.fetchKyc.mockResolvedValue({ status: "APPROVED", level: "INTERMEDIATE", submittedAt: null, reviewedAt: null, expiresAt: null, rejectionReason: null });
  my.fetchMfa.mockResolvedValue({ enabled: true, recoveryCodesRemaining: 8, enabledAt: null });
  markets.fetchSymbols.mockResolvedValue([{ symbol: "ETHBTC", quoteAsset: "BTC" }, { symbol: "BTCUSDT", quoteAsset: "USDT" }]);
  api.fetchLeadApiKeys.mockResolvedValue([]);
  api.revokeLeadApiKey.mockResolvedValue(undefined);
  sessionStorage.clear();
});
afterEach(() => vi.clearAllMocks());

describe("Leaders", () => {
  it("shows each leader as a card, best month first; a full leader is shown but not offered", async () => {
    withClient(<Leaders />);
    const mina = await screen.findByRole("listitem", { name: "Mina" });
    expect(within(mina).getByRole("link", { name: "Copy" })).toHaveAttribute("href", "/en/copy/L1");
    expect(within(mina).getByText("+1,250.5")).toHaveClass("text-up");
    expect(within(mina).getByText("+12.34%")).toBeInTheDocument();
    expect(within(mina).getByText("4.2%")).toBeInTheDocument();
    expect(within(mina).getByText("25,000 USDT")).toBeInTheDocument();
    expect(within(mina).getByText("Followers 12 / 300")).toBeInTheDocument();
    expect(within(mina).getByRole("img", { name: "30-day return, day by day" })).toBeInTheDocument();
    const joon = screen.getByRole("listitem", { name: "Joon" });
    expect(within(joon).queryByRole("link", { name: "Copy" })).toBeNull();
    expect(within(joon).getByText("Full")).toBeInTheDocument();
    // No figures yet: zeros and a flat line, like any other card.
    expect(within(joon).queryByText("—")).toBeNull();
    expect(within(joon).getByText("0", { selector: "span.text-2xl" })).toBeInTheDocument();
    expect(within(joon).getAllByText("0.00%")).toHaveLength(2);   // return and max drawdown
    expect(within(joon).getByText("0 USDT")).toBeInTheDocument();
    expect(within(joon).getByRole("img", { name: "30-day return, day by day" })).toBeInTheDocument();
    expect(screen.getAllByRole("listitem").map((c) => c.getAttribute("aria-label"))).toEqual(["Mina", "Joon"]);
  });

  it("a signed-in customer sees what they have copying and the way to their copy accounts; a visitor only the way to lead", async () => {
    withClient(<Leaders />);
    expect(await screen.findByText("Copying (USDT value)")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("1,000")).toBeInTheDocument());
    expect(screen.getByRole("link", { name: "Copy overview" })).toHaveAttribute("href", "/en/copy/mine");
    expect(screen.getByRole("link", { name: "Be a lead trader" })).toHaveAttribute("href", "/en/copy/lead");

    session.status = "signedOut";
    withClient(<Leaders />);
    await screen.findAllByRole("link", { name: "Be a lead trader" });
    expect(screen.getAllByText("Copying (USDT value)")).toHaveLength(1);
    expect(api.fetchFollows).toHaveBeenCalledTimes(1);
    // A visitor's Copy goes straight to the login.
    await waitFor(() => expect(screen.getAllByRole("link", { name: "Copy" })).toHaveLength(2));
    expect(screen.getAllByRole("link", { name: "Copy" }).map((l) => l.getAttribute("href"))).toEqual(["/en/copy/L1", "/en/login"]);
  });

  it("stars a leader for the Favorites tab, kept in this browser", async () => {
    const user = userEvent.setup();
    localStorage.clear();
    withClient(<Leaders />);
    await user.click(await screen.findByRole("tab", { name: "Favorites" }));
    expect(screen.getByText("No favorites yet")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "All" }));
    await user.click(within(screen.getByRole("listitem", { name: "Joon" })).getByRole("button", { name: "Add to favorites" }));
    await user.click(screen.getByRole("tab", { name: "Favorites" }));
    expect(screen.getAllByRole("listitem").map((c) => c.getAttribute("aria-label"))).toEqual(["Joon"]);
    expect(localStorage.getItem("favorite-leaders")).toBe("L2");
  });

  it("finds a leader by name, and sorts by the figure chosen", async () => {
    const user = userEvent.setup();
    api.fetchLeaders.mockResolvedValue({ terms: TERMS, leaders: [MINA,
      { ...FULL, accepting: true, stats: { ...MINA.stats, days30: { return: "2", pnl: "9000", maxDrawdown: "1.5" }, days7: { return: "9", pnl: "1", maxDrawdown: "9" }, fundsCopying: "90000" } }] });
    withClient(<Leaders />);
    const names = () => screen.getAllByRole("listitem").map((c) => c.getAttribute("aria-label"));
    await screen.findByRole("listitem", { name: "Mina" });
    expect(names()).toEqual(["Mina", "Joon"]);                      // by 30-day return
    const pick = async (menu: string, item: string) => {
      await user.click(screen.getByRole("button", { name: menu }));
      await user.click(screen.getByRole("menuitemradio", { name: item }));
    };
    await pick("Sort by", "PNL");
    expect(names()).toEqual(["Joon", "Mina"]);
    await pick("Sort by", "Max drawdown");
    expect(names()).toEqual(["Joon", "Mina"]);                      // 1.5% before 4.2%
    await pick("Sort by", "Followers");
    expect(names()).toEqual(["Joon", "Mina"]);                      // 300 before 12
    // The window changes which figures are sorted and shown.
    await pick("Sort by", "Return");
    await pick("Period", "7D");
    expect(names()).toEqual(["Joon", "Mina"]);                      // 9% before 1.2% over the week
    expect(screen.getByRole("button", { name: "Period" })).toHaveTextContent("7D");
    expect(within(screen.getByRole("listitem", { name: "Mina" })).getByText("7D PNL (USDT)")).toBeInTheDocument();
    expect(within(screen.getByRole("listitem", { name: "Mina" })).getByText("+80")).toBeInTheDocument();

    await user.type(screen.getByRole("textbox", { name: "Search by name" }), "mi");
    expect(names()).toEqual(["Mina"]);
    await user.type(screen.getByRole("textbox", { name: "Search by name" }), "x");
    expect(screen.getByText("No leader found")).toBeInTheDocument();
  });

  it("shows twelve cards a page, and a search that leaves fewer lands on the last page there is", async () => {
    const user = userEvent.setup();
    const many = Array.from({ length: 27 }, (_, i) => ({ ...MINA, id: `l${i}`, displayName: `Leader ${String(i).padStart(2, "0")}` }));
    api.fetchLeaders.mockResolvedValue({ terms: TERMS, leaders: many });
    withClient(<Leaders />);
    await screen.findByRole("listitem", { name: "Leader 00" });
    expect(screen.getAllByRole("listitem")).toHaveLength(12);
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Search by name" }), "Leader 1");
    expect(screen.getAllByRole("listitem")).toHaveLength(10);
    expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
  });

  it("answers the questions with the terms staff set", async () => {
    const user = userEvent.setup();
    withClient(<Leaders />);
    await user.click(await screen.findByText("What happens when a copy fails?"));
    expect(screen.getByText(/After 5 failed copies in a row/)).toBeInTheDocument();
    expect(screen.getByText(/10% of the profit you realize/)).toBeInTheDocument();
    expect(screen.getByText(/at least 500 USDT/)).toBeInTheDocument();
  });

  it("says so when there is nobody to follow", async () => {
    api.fetchLeaders.mockResolvedValue({ leaders: [], terms: TERMS });
    withClient(<Leaders />);
    expect(await screen.findByText("No leaders yet")).toBeInTheDocument();
  });
});

describe("Leader profile", () => {
  it("shows the terms and the lead account before anyone commits money, and a visitor is asked to sign in", async () => {
    session.status = "signedOut";
    withClient(<LeaderProfile id="L1" />);
    expect(await screen.findByRole("link", { name: "Sign in to copy" })).toHaveAttribute("href", "/en/login");
    expect(screen.queryByRole("button", { name: "Copy" })).toBeNull();
    // One card: the figures, then the account's own lines. The minimums are the form's to say.
    const performance = screen.getByRole("region", { name: "Performance" });
    // The leader's own rate (15%), not the exchange's (10% in TERMS).
    expect(within(performance).getByText("Profit share").nextElementSibling).toHaveTextContent("15%");
    expect(within(performance).getByText("Leading balance").nextElementSibling).toHaveTextContent("12,500.5 USDT");
    expect(within(performance).queryByText("Last trade")).toBeNull();
    expect(screen.queryByRole("region", { name: "Overview" })).toBeNull();
    expect(screen.queryByText("Minimum to follow, fixed ratio")).toBeNull();
    expect(screen.getByText("Followers").nextElementSibling).toHaveTextContent("12/300");
    // Holdings largest first with their share; trades behind the other tab.
    const btc = screen.getByRole("cell", { name: "BTC" }).closest("tr")!;
    expect(within(btc).getByText("25.0%")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "30-day return, day by day" })).toBeInTheDocument();
  });

  it("a leader who keeps their positions private shows the figures and nothing else; the trades tab lists fills", async () => {
    const user = userEvent.setup();
    withClient(<LeaderProfile id="L1" />);
    await user.click(await screen.findByRole("tab", { name: "Trade history" }));
    expect(await screen.findByRole("cell", { name: "BTCUSDT" })).toBeInTheDocument();
    expect(api.fetchLeaderTrades.mock.calls[0].slice(0, 2)).toEqual(["L1", 0]);

    api.fetchLeader.mockResolvedValue({ ...PROFILE, leader: { ...MINA, portfolioShown: false }, portfolio: null });
    document.body.innerHTML = "";
    withClient(<LeaderProfile id="L1" />);
    expect(await screen.findByText("This leader keeps their positions private")).toBeInTheDocument();
    expect(screen.getByText("Private")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Performance" })).getByText("+12.34%")).toBeInTheDocument();
  });

  it("pages the leader's trades from the server, twenty at a time", async () => {
    const user = userEvent.setup();
    api.fetchLeaderTrades.mockImplementation(async (_id: string, page: number) =>
      page === 0 ? Array.from({ length: 20 }, () => TRADE) : [TRADE]);
    withClient(<LeaderProfile id="L1" />);
    await user.click(await screen.findByRole("tab", { name: "Trade history" }));
    await user.click(await screen.findByRole("button", { name: "Next" }));
    await waitFor(() => expect(api.fetchLeaderTrades.mock.calls.some((c) => c[1] === 1)).toBe(true));
  });

  it("starts a fixed-ratio copy with the amount, under an id made once, and goes to the new account", async () => {
    const user = userEvent.setup();
    withClient(<LeaderProfile id="L1" />);
    await user.click(await screen.findByRole("button", { name: "Copy" }));
    const start = await screen.findByRole("button", { name: "Start copying" });
    expect(start).toBeDisabled();

    await user.type(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }), "50");
    expect(screen.getByText("Minimum 100 USDT")).toBeInTheDocument();
    expect(start).toBeDisabled();
    await user.clear(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }));
    await user.type(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }), "500");
    await user.click(start);

    await waitFor(() => expect(api.follow).toHaveBeenCalledTimes(1));
    const sent = api.follow.mock.calls[0][0];
    expect(sent).toMatchObject({ leaderId: "L1", mode: "FIXED_RATIO", amount: "500", slippageCapBps: 50 });
    expect(sent.followId).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent).not.toHaveProperty("amountPerOrder");
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith("/en/copy/mine/new-follow"));
  });

  it("arriving from Copy again opens the copy form straight away", async () => {
    withClient(<LeaderProfile id="L1" startCopying />);
    expect(await screen.findByRole("button", { name: "Start copying" })).toBeInTheDocument();
  });

  it("the follower sets their own max slippage, as a percentage within 0.1% to 5%", async () => {
    const user = userEvent.setup();
    withClient(<LeaderProfile id="L1" />);
    await user.click(await screen.findByRole("button", { name: "Copy" }));
    const start = await screen.findByRole("button", { name: "Start copying" });
    await user.type(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }), "500");
    const slippage = screen.getByRole("textbox", { name: /^Max slippage/ });
    expect(slippage).toHaveValue("0.5");

    await user.clear(slippage);
    await user.type(slippage, "8");
    expect(start).toBeDisabled();
    await user.clear(slippage);
    await user.type(slippage, "1.2");
    await user.click(start);
    await waitFor(() => expect(api.follow.mock.calls[0][0]).toMatchObject({ slippageCapBps: 120 }));
  });

  it("a fixed amount needs an amount per order the funding covers, and more than the balance is refused here", async () => {
    const user = userEvent.setup();
    withClient(<LeaderProfile id="L1" />);
    await user.click(await screen.findByRole("button", { name: "Copy" }));
    const start = await screen.findByRole("button", { name: "Start copying" });

    await user.click(screen.getByRole("radio", { name: "Fixed amount" }));
    await user.type(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }), "60");
    expect(start).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: /^Per order \(USDT\)/ }), "80");
    expect(start).toBeDisabled();
    await user.clear(screen.getByRole("textbox", { name: /^Per order \(USDT\)/ }));
    await user.type(screen.getByRole("textbox", { name: /^Per order \(USDT\)/ }), "20");
    await user.click(start);
    await waitFor(() => expect(api.follow.mock.calls[0][0]).toMatchObject({ mode: "FIXED_AMOUNT", amount: "60", amountPerOrder: "20" }));

    await user.clear(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }));
    await user.type(screen.getByRole("textbox", { name: /^Amount \(USDT\)/ }), "5000");
    expect(start).toBeDisabled();
  });
});

describe("My copies", () => {
  it("lists each copy account with its state and its balance, and the way in", async () => {
    withClient(<MyCopies />);
    const row = (await screen.findByText("Mina")).closest("tr")!;
    expect(within(row).getByText("Copying")).toBeInTheDocument();
    expect(await within(row).findByText("1,000.00 USDT")).toBeInTheDocument();
    expect(within(row).getByRole("link", { name: "Details" })).toHaveAttribute("href", "/en/copy/mine/F1");
  });

  it("with none, says so", async () => {
    api.fetchFollows.mockResolvedValue([]);
    withClient(<MyCopies />);
    expect(await screen.findByText("No copies yet")).toBeInTheDocument();
  });
});

describe("Copy account", () => {
  it("opens on what it holds; the copies tab shows what each traded, or why it did not happen", async () => {
    const user = userEvent.setup();
    withClient(<CopyAccount id="F1" />);
    expect(await screen.findByText(/2 failed in a row/)).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "BTC" })).toBeInTheDocument();
    expect(screen.queryByText("ETHUSDT")).toBeNull();

    await user.click(screen.getByRole("tab", { name: "Copies" }));
    const filled = (await screen.findByText("BTCUSDT")).closest("tr")!;
    expect(within(filled).getByText("0.002")).toBeInTheDocument();
    expect(within(filled).getByText("63,250.5")).toBeInTheDocument();
    expect(within(filled).getByText("126.501")).toBeInTheDocument();
    const skipped = screen.getByText("ETHUSDT").closest("tr")!;
    expect(within(skipped).getByText(/Skipped/)).toBeInTheDocument();
    expect(within(skipped).getByText(/Nothing to sell/)).toBeInTheDocument();
    expect(api.fetchCopies.mock.calls[0].slice(0, 2)).toEqual(["F1", 0]);
  });

  it("pages the copies from the server: a full page offers the next one", async () => {
    const user = userEvent.setup();
    const one = (n: number) => ({ id: `C${n}`, symbol: "BTCUSDT", side: "BUY", status: "FILLED", reason: null, orderId: `O${n}`,
      closing: false, createdAt: "2026-09-11T10:00:00Z", filled: "0.001", averagePrice: "60000", total: "60" });
    api.fetchCopies.mockImplementation(async (_id: string, page: number) =>
      page === 0 ? Array.from({ length: 20 }, (_, i) => one(i)) : [one(99)]);
    withClient(<CopyAccount id="F1" />);
    await user.click(await screen.findByRole("tab", { name: "Copies" }));
    await user.click(await screen.findByRole("button", { name: "Next" }));
    await waitFor(() => expect(api.fetchCopies.mock.calls.some((c) => c[1] === 1)).toBe(true));
  });

  it("shows the profit share first, and each settlement on its own tab", async () => {
    const user = userEvent.setup();
    withClient(<CopyAccount id="F1" />);
    expect(await screen.findByText("Your rate")).toBeInTheDocument();
    expect(screen.getByText("Your rate").nextElementSibling).toHaveTextContent("10%");
    expect(screen.getByText("Realized profit").nextElementSibling).toHaveTextContent("48.5 USDT");
    expect(screen.getByText("Shared with the leader").nextElementSibling).toHaveTextContent("4.85 USDT");
    await user.click(screen.getByRole("tab", { name: "Profit share" }));
    const row = (await screen.findByText("The week's end")).closest("tr")!;
    expect(within(row).getAllByText("4.85")).toHaveLength(2);
  });

  it("stopping asks first", async () => {
    const user = userEvent.setup();
    withClient(<CopyAccount id="F1" />);
    await user.click(await screen.findByRole("button", { name: "Stop copying" }));
    expect(api.stopCopying).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.stopCopying.mock.calls[0][0]).toBe("F1"));
  });

  it("adds funds from the main account's balance and takes out from the copy account's, each under its own id", async () => {
    const user = userEvent.setup();
    withClient(<CopyAccount id="F1" />);
    await user.click(await screen.findByRole("button", { name: "Add funds" }));
    let dialog = screen.getByRole("dialog");
    expect(await within(dialog).findByText("Available: 1000 USDT")).toBeInTheDocument();
    await user.type(within(dialog).getByRole("textbox"), "100");
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.addFunds.mock.calls[0][0]).toMatchObject({ id: "F1", amount: "100" }));

    await user.click(screen.getByRole("button", { name: "Take out" }));
    dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Available: 400 USDT")).toBeInTheDocument();
    await user.type(within(dialog).getByRole("textbox"), "500");
    expect(within(dialog).getByRole("button", { name: "Confirm" })).toBeDisabled();
    await user.clear(within(dialog).getByRole("textbox"));
    await user.type(within(dialog).getByRole("textbox"), "200");
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.removeFunds.mock.calls[0][0]).toMatchObject({ id: "F1", amount: "200" }));
    expect(api.removeFunds.mock.calls[0][0].transferId).not.toBe(api.addFunds.mock.calls[0][0].transferId);
  });

  it("a closed account offers nothing to do, and says how it ended", async () => {
    api.fetchFollow.mockResolvedValue({ follow: { ...FOLLOW, status: "CLOSED", closeReason: "FAILURES", holdings: [] }, shares: [] });
    withClient(<CopyAccount id="F1" />);
    expect(await screen.findByText(/Closed after failed copies/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Stop copying" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Add funds" })).toBeNull();
    // Copying again is a new follow, on the leader's form.
    expect(screen.getByRole("link", { name: "Copy again" })).toHaveAttribute("href", "/en/copy/L1?copy=1");
  });
});

describe("Lead", () => {
  const page = (lead: object | null) => ({ lead, quoteAsset: "USDT", minBalance: "500", maxBalance: "1000000" });
  const LEADING = { id: "L9", displayName: "Mina", bio: null, status: "ACTIVE", followers: 3, hasAccount: true, portfolioShown: true, earned: "120.5",
    holdings: [{ asset: "USDT", available: "800", locked: "0" }, { asset: "BTC", available: "0.5", locked: "0" }] };

  it("someone who never applied starts leading in one step: name, what they show, and what moves in", async () => {
    const user = userEvent.setup();
    api.fetchLead.mockResolvedValue(page(null));
    withClient(<Lead />);
    const start = await screen.findByRole("button", { name: "Start leading" });
    expect(start).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Display name" }), "  Mina K ");
    await user.type(screen.getByRole("textbox", { name: "About your trading" }), "swing");
    await user.type(screen.getByRole("textbox", { name: /^Start with \(USDT\)/ }), "400");
    expect(start).toBeDisabled();                                   // under the 500 minimum
    await user.clear(screen.getByRole("textbox", { name: /^Start with \(USDT\)/ }));
    await user.type(screen.getByRole("textbox", { name: /^Start with \(USDT\)/ }), "800");
    await user.click(screen.getByRole("checkbox"));                 // keep the positions private
    await user.click(start);
    await waitFor(() => expect(api.applyToLead.mock.calls[0][0]).toMatchObject({ displayName: "Mina K", bio: "swing", showPortfolio: false, amount: "800" }));
    expect(api.applyToLead.mock.calls[0][0].transferId).toMatch(/[0-9a-f-]{36}/);
  });

  it("a leader takes out only what is above the minimum, in USDT, and stopping asks first", async () => {
    const user = userEvent.setup();
    api.fetchLead.mockResolvedValue(page(LEADING));
    withClient(<Lead />);
    expect(await screen.findByText("Keeps 500 USDT while it leads.")).toBeInTheDocument();
    expect(screen.getByText(/Earned 120.5 USDT/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Take out" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Available: 300 USDT")).toBeInTheDocument();
    expect(within(dialog).queryByRole("combobox")).toBeNull();
    await user.type(within(dialog).getByRole("textbox"), "301");
    expect(within(dialog).getByRole("button", { name: "Confirm" })).toBeDisabled();
    await user.clear(within(dialog).getByRole("textbox"));
    await user.type(within(dialog).getByRole("textbox"), "300");
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.withdrawLead.mock.calls[0][0]).toMatchObject({ asset: "USDT", amount: "300" }));

    await user.click(screen.getByRole("tab", { name: "Settings" }));
    await user.click(screen.getByRole("button", { name: "Stop leading" }));
    expect(api.stopLeading).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.stopLeading).toHaveBeenCalled());
  });

  it("a leader staff stopped is told only that it is not available, and may take everything out", async () => {
    const user = userEvent.setup();
    api.fetchLead.mockResolvedValue(page({ ...LEADING, status: "UNAVAILABLE", followers: 0 }));
    withClient(<Lead />);
    expect(await screen.findByText("Not available")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/suspend/i);
    expect(screen.queryByRole("button", { name: "Add funds" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Stop leading" })).toBeNull();

    await user.click(screen.getByRole("button", { name: "Take out" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Available: 800 USDT")).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByRole("combobox"), "BTC");
    expect(within(dialog).getByText("Available: 0.5 BTC")).toBeInTheDocument();
  });

  it("a leader is sent to a market the lead account can trade, as the lead account", async () => {
    const user = userEvent.setup();
    api.fetchLead.mockResolvedValue(page(LEADING));
    withClient(<Lead />);
    const trade = await screen.findByRole("link", { name: "Trade" });
    expect(trade).toHaveAttribute("href", expect.stringMatching(/\/trade\/BTCUSDT$/));
    trade.addEventListener("click", (e) => e.preventDefault());
    await user.click(trade);
    expect(sessionStorage.getItem("trading-account")).toBe("lead");
  });

  it("the lead account's keys are listed and deleted here, deleting asks first, and none is ever edited", async () => {
    const user = userEvent.setup();
    api.fetchLead.mockResolvedValue(page(LEADING));
    api.fetchLeadApiKeys.mockResolvedValue([
      { id: "K1", apiKey: "ak_live_1234567890", label: "bot", canRead: true, canTrade: true, canWithdraw: false, ipAllowlist: [],
        lastUsedAt: null, expiresAt: null, createdAt: "2026-10-01T00:00:00Z", revokedAt: null, revokedReason: null },
      { id: "K0", apiKey: "ak_gone_1234567890", label: "old", canRead: true, canTrade: false, canWithdraw: false, ipAllowlist: [],
        lastUsedAt: null, expiresAt: null, createdAt: "2026-09-01T00:00:00Z", revokedAt: "2026-09-02T00:00:00Z", revokedReason: "REVOKED_BY_USER" },
    ]);
    withClient(<Lead />);
    await user.click(await screen.findByRole("tab", { name: "API Management" }));
    const card = await screen.findByRole("article", { name: "bot" });
    expect(screen.queryByRole("article", { name: "old" })).toBeNull();
    expect(within(card).queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.getByRole("link", { name: "+ Create API key" })).toHaveAttribute("href", "/en/copy/lead/keys/create");

    await user.click(within(card).getByRole("button", { name: "Delete" }));
    expect(api.revokeLeadApiKey).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.revokeLeadApiKey.mock.calls[0][0]).toBe("K1"));
  });

  it("a leader sees the figures a follower sees, from their own public profile", async () => {
    api.fetchLead.mockResolvedValue(page(LEADING));
    api.fetchLeader.mockResolvedValue({ ...PROFILE, leader: { ...MINA, id: "L9" } });
    withClient(<Lead />);
    const performance = await screen.findByRole("region", { name: "Performance" });
    expect(await within(performance).findByText("+12.34%")).toBeInTheDocument();
    expect(api.fetchLeader).toHaveBeenCalledWith("L9", expect.anything());
    // The account's own figures sit in the same card, as on the public profile.
    expect(within(performance).getByText("Leading balance").nextElementSibling).toHaveTextContent("12,500.5 USDT");
    expect(within(performance).getByText("Earned in total").nextElementSibling).toHaveTextContent("120.5 USDT");
    expect(screen.queryByRole("region", { name: "Overview" })).toBeNull();
  });

  it("a leader who stopped sees the state and what is left, and the form only when they ask to lead again", async () => {
    const user = userEvent.setup();
    api.fetchLead.mockResolvedValue(page({ ...LEADING, status: "CLOSED", followers: 0, holdings: [{ asset: "USDT", available: "20", locked: "0" }] }));
    withClient(<Lead />);
    expect(await screen.findByText("Closed")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Display name" })).toBeNull();
    expect(screen.queryByRole("tab")).toBeNull();
    expect(screen.getByRole("button", { name: "Take out" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Lead again" }));
    expect(within(screen.getByRole("dialog")).getByRole("textbox", { name: "Display name" })).toHaveValue("Mina");
  });
});
