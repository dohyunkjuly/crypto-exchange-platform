import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchTreasury: vi.fn(), fetchRevenue: vi.fn(), moveFees: vi.fn(), payOut: vi.fn(), fetchMoves: vi.fn() }));
const session = vi.hoisted(() => ({ role: "VIEWER" }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }),
  usePathname: () => "/treasury",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { Treasury } = await import("./Treasury");

/** Net is what is left after the rebates, so the three columns are three numbers. */
const flow = (fees: number, rebates: number) => ({
  fees: String(fees), rebates: String(rebates), net: String(fees - rebates),
});
const USDT = {
  symbol: "USDT", name: "Tether", displayDecimals: 2,
  customerAvailable: "500", customerLocked: "100", customerTotal: "600", holders: 3,
  feeAccount: "7", treasuryAccount: "0",
  periods: { today: flow(3, 1), week: flow(21, 4), month: flow(90, 12), all: flow(400, 30) },
};
const BTC = { ...USDT, symbol: "BTC", name: "Bitcoin", displayDecimals: 5,
  periods: { today: flow(0, 0), week: flow(0, 0), month: flow(0, 0), all: flow(0, 0) } };

beforeEach(() => {
  nav.query = "";
  session.role = "VIEWER";
  api.fetchTreasury.mockResolvedValue({ assets: [BTC, USDT], sweptAt: "2026-09-23T14:05:00Z" });
  api.fetchRevenue.mockResolvedValue(Array.from({ length: 30 }, (_, i) => ({
    day: `2026-09-${String(i + 1).padStart(2, "0")}`, fees: i === 29 ? "3" : "1", rebates: "0",
  })));
});
afterEach(() => vi.clearAllMocks());

describe("Treasury", () => {
  it("opens on what is held, with the exchange's own accounts beside customers', never inside", async () => {
    render(<Treasury />);

    const row = (await screen.findByText("USDT")).closest("tr")!;
    expect(within(row).getByText("600.00")).toBeInTheDocument();  // customers
    expect(within(row).getByText("7.00")).toBeInTheDocument();    // the fee account, its own column
    expect(screen.getByRole("tab", { name: "Held now" })).toHaveAttribute("aria-selected", "true");
    // No window applies to a reading taken now, so no period control is offered here.
    expect(screen.queryByRole("tab", { name: "All time" })).toBeNull();
  });

  it("puts the view in the URL, so a refresh keeps it", async () => {
    const user = userEvent.setup();
    render(<Treasury />);
    await screen.findByText("USDT");

    await user.click(screen.getByRole("tab", { name: "Earned" }));
    expect(nav.replace).toHaveBeenCalledWith("/treasury?view=earned");
  });

  it("on Earned, the period changes the table without asking the server again", async () => {
    nav.query = "view=earned";
    const user = userEvent.setup();
    render(<Treasury />);

    // The asset selector also says USDT; the row is the cell that does.
    const row = (await screen.findByRole("cell", { name: /USDT/ })).closest("tr")!;
    expect(within(row).getByText("3.00")).toBeInTheDocument();
    expect(within(row).getByText("2.00")).toBeInTheDocument();   // net, its own number

    await user.click(screen.getByRole("tab", { name: "All time" }));
    expect(within(row).getByText("400.00")).toBeInTheDocument();
    expect(within(row).getByText("370.00")).toBeInTheDocument();
    expect(api.fetchTreasury).toHaveBeenCalledTimes(1);
  });

  it("charts one asset at a time, opening on the first that has earned anything", async () => {
    nav.query = "view=earned";
    render(<Treasury />);

    // BTC is listed first and has earned nothing; the chart starts on USDT instead.
    expect(await screen.findByRole("combobox", { name: "Asset charted" })).toHaveValue("USDT");
    expect(api.fetchRevenue).toHaveBeenCalledWith("USDT", 30);
    expect(await screen.findByRole("img", { name: /USDT fees per day/ })).toBeInTheDocument();
  });

  it("pages at twenty assets and filters by symbol or name, landing on a page that exists", async () => {
    const user = userEvent.setup();
    const many = Array.from({ length: 25 }, (_, i) => ({ ...USDT, symbol: `A${String(i).padStart(2, "0")}`, name: `Asset ${i}` }));
    api.fetchTreasury.mockResolvedValue({ assets: [...many, { ...USDT, symbol: "ZZZ", name: "Zed" }], sweptAt: null });
    render(<Treasury />);

    await screen.findByRole("cell", { name: /A00/ });
    expect(screen.getAllByRole("row")).toHaveLength(21); // twenty assets and the header
    expect(screen.queryByRole("cell", { name: /ZZZ/ })).toBeNull();

    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("cell", { name: /ZZZ/ })).toBeInTheDocument();

    // One match while standing on page two: the row shows rather than an empty table.
    await user.type(screen.getByRole("textbox", { name: "Filter assets" }), "zed");
    expect(screen.getByRole("cell", { name: /ZZZ/ })).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(2);

    await user.clear(screen.getByRole("textbox", { name: "Filter assets" }));
    await user.type(screen.getByRole("textbox", { name: "Filter assets" }), "nothing");
    expect(screen.getByText("No asset matches.")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Fee account" })).toBeInTheDocument();
  });

  it("says loudly when no fees have ever been settled, since the account would read zero", async () => {
    api.fetchTreasury.mockResolvedValue({ assets: [USDT], sweptAt: null });
    render(<Treasury />);

    expect(await screen.findByRole("alert")).toHaveTextContent("No fees have been settled yet.");
    expect(screen.getByRole("columnheader", { name: "Fee account" })).toBeInTheDocument();
  });

  it("keeps its columns when the exchange holds nothing at all", async () => {
    api.fetchTreasury.mockResolvedValue({ assets: [], sweptAt: null });
    render(<Treasury />);

    expect(await screen.findByRole("columnheader", { name: "Fee account" })).toBeInTheDocument();
    expect(screen.getByText("Nothing is held yet.")).toBeInTheDocument();
  });

  it("moves nothing for a VIEWER: no move or payout is offered", async () => {
    render(<Treasury />);
    await screen.findByText("USDT");
    expect(screen.queryByRole("button", { name: "Move to treasury" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Pay a customer" })).toBeNull();
  });

  it("an OPERATOR moves fees to the treasury, the amount opening at what the fee account holds", async () => {
    session.role = "OPERATOR";
    const user = userEvent.setup();
    api.moveFees.mockResolvedValue({ assets: [BTC, { ...USDT, feeAccount: "0", treasuryAccount: "7" }], sweptAt: "2026-09-23T14:05:00Z" });
    render(<Treasury />);

    const row = (await screen.findByText("USDT")).closest("tr")!;
    await user.click(within(row).getByRole("button", { name: "Move to treasury" }));
    expect(screen.getByLabelText("Amount (USDT)")).toHaveValue("7");
    await user.type(screen.getByLabelText("Reason"), "monthly");
    await user.type(screen.getByLabelText("Authenticator code"), "123456");
    await user.click(screen.getByRole("button", { name: "Move" }));

    expect(api.moveFees).toHaveBeenCalledWith({ id: expect.any(String), asset: "USDT", amount: "7", reason: "monthly" }, "123456");
    // The page shows what the server says now, not a guess.
    const after = (await screen.findByText("USDT")).closest("tr")!;
    expect(within(after).queryByRole("button", { name: "Move to treasury" })).toBeNull();
  });

  it("an OPERATOR pays a customer out of what the treasury holds", async () => {
    session.role = "OPERATOR";
    const user = userEvent.setup();
    api.fetchTreasury.mockResolvedValue({ assets: [BTC, { ...USDT, treasuryAccount: "50" }], sweptAt: "2026-09-23T14:05:00Z" });
    api.payOut.mockResolvedValue({ assets: [BTC, { ...USDT, treasuryAccount: "46" }], sweptAt: "2026-09-23T14:05:00Z" });
    render(<Treasury />);

    await user.click(await screen.findByRole("button", { name: "Pay a customer" }));
    expect(screen.getByText("The treasury holds 50.00 USDT.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Customer (email or id)"), "ann@x.dev");
    await user.type(screen.getByLabelText("Amount"), "4");
    await user.type(screen.getByLabelText("Reason"), "outage credit");
    await user.type(screen.getByLabelText("Authenticator code"), "123456");
    await user.click(screen.getByRole("button", { name: "Pay" }));

    expect(api.payOut).toHaveBeenCalledWith(
      { id: expect.any(String), customer: "ann@x.dev", asset: "USDT", amount: "4", reason: "outage credit" }, "123456");
  });

  it("lists the latest moves, with who moved what to whom and why", async () => {
    nav.query = "view=moves";
    api.fetchMoves.mockResolvedValue([
      { at: "2026-10-08T10:00:00Z", kind: "PAYOUT", admin: "ops@x.dev", asset: "USDT", amount: "4", to: "ann@x.dev", reason: "outage credit" },
      { at: "2026-10-08T09:00:00Z", kind: "FEES_TO_TREASURY", admin: "ops@x.dev", asset: "BTC", amount: "0.5", to: null, reason: "monthly" },
    ]);
    render(<Treasury />);

    expect(await screen.findByText("Treasury → ann@x.dev")).toBeInTheDocument();
    expect(screen.getByText("Fees → treasury")).toBeInTheDocument();
    expect(screen.getByText("outage credit")).toBeInTheDocument();
  });
});
