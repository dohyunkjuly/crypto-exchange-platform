import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchCopySettings: vi.fn(), saveCopySettings: vi.fn() }));
const session = vi.hoisted(() => ({ role: "OPERATOR" }));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
const { Settings } = await import("./Settings");

const settings = {
  profitShareBps: 1000, failureLimit: 5, maxFollowersPerLeader: 300, maxCopiesPerUser: 10,
  minFixedRatioAmount: "100", minFixedAmount: "10", leadMinBalance: "500", leadMaxBalance: "1000000", closeSellThreshold: "10",
  updatedAt: "2026-10-01T00:00:00Z",
};
const code = "123456";

beforeEach(() => {
  session.role = "OPERATOR";
  api.fetchCopySettings.mockResolvedValue(settings);
});
afterEach(() => vi.clearAllMocks());

describe("Copy settings", () => {
  it("shows every setting as staff read it, and no slippage cap: that is each follower's", async () => {
    render(<Settings />);
    const share = (await screen.findByText("Leader's share of a follower's profit, for anyone who follows from now on")).closest("tr")!;
    expect(within(share).getByText("bps · 10.00%")).toBeInTheDocument();
    expect(within(screen.getByText("Followers per leader").closest("tr")!).getByText("300")).toBeInTheDocument();
    expect(screen.queryByText(/slippage/i)).toBeNull();
  });

  it("saves the whole row with a code, numbers as numbers and amounts as typed", async () => {
    const u = userEvent.setup();
    api.saveCopySettings.mockResolvedValue({ ...settings, profitShareBps: 1500 });
    render(<Settings />);
    await u.click(await screen.findByRole("button", { name: "Edit settings" }));
    const dialog = screen.getByRole("dialog");
    const save = within(dialog).getByRole("button", { name: "Save" });
    await u.clear(within(dialog).getByRole("textbox", { name: "Leader's share of a follower's profit, for anyone who follows from now on" }));
    await u.type(within(dialog).getByRole("textbox", { name: "Leader's share of a follower's profit, for anyone who follows from now on" }), "1500");
    await u.clear(within(dialog).getByRole("textbox", { name: "What a lead account keeps (USDT)" }));
    await u.type(within(dialog).getByRole("textbox", { name: "What a lead account keeps (USDT)" }), "750.5");
    expect(save).toBeDisabled();                           // no code yet
    await u.type(within(dialog).getByLabelText("Your authenticator code"), code);
    await u.click(save);

    expect(api.saveCopySettings).toHaveBeenCalledWith({
      profitShareBps: 1500, failureLimit: 5, maxFollowersPerLeader: 300, maxCopiesPerUser: 10,
      minFixedRatioAmount: "100", minFixedAmount: "10", leadMinBalance: "750.5", leadMaxBalance: "1000000", closeSellThreshold: "10",
    }, code);
    expect(await screen.findByRole("status")).toHaveTextContent("apply from the next copy");
    expect(within(screen.getByText("Leader's share of a follower's profit, for anyone who follows from now on").closest("tr")!).getByText("bps · 15.00%")).toBeInTheDocument();
  });

  it("will not send a rate that is not a whole number", async () => {
    const u = userEvent.setup();
    render(<Settings />);
    await u.click(await screen.findByRole("button", { name: "Edit settings" }));
    const dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByLabelText("Your authenticator code"), code);
    await u.clear(within(dialog).getByRole("textbox", { name: "Failed copies in a row that close a copy account" }));
    await u.type(within(dialog).getByRole("textbox", { name: "Failed copies in a row that close a copy account" }), "0.5");
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("a VIEWER reads the settings and is offered no change", async () => {
    session.role = "VIEWER";
    render(<Settings />);
    await screen.findByText("Followers per leader");
    expect(screen.queryByRole("button")).toBeNull();
  });
});
