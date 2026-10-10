import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchLeaders: vi.fn(), decideLeader: vi.fn(), setLeaderProfitShare: vi.fn() }));
const session = vi.hoisted(() => ({ role: "OPERATOR" }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }), usePathname: () => "/copy/leaders",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
const { Leaders } = await import("./Leaders");

const leader = {
  id: "0b6f8a52-5d0e-4c58-9f0e-1c2d3e4f5a6b", userId: 7, email: "ann@x.dev", displayName: "Ann Trades", bio: "Majors only",
  status: "ACTIVE", followers: 3, leadUserId: 9, appliedAt: "2026-10-01T09:30:00Z", reviewedAt: null, reviewReason: null,
};
const page = (...items: object[]) => ({ items, total: items.length, page: 0, size: 25 });

beforeEach(() => {
  nav.query = "";
  session.role = "OPERATOR";
  api.fetchLeaders.mockResolvedValue(page(leader));
});
afterEach(() => vi.clearAllMocks());

describe("Copy leaders", () => {
  it("lists who is leading by default; suspending says how many copy accounts it closes, and needs a reason and a code", async () => {
    const u = userEvent.setup();
    api.decideLeader.mockResolvedValue({ ...leader, status: "SUSPENDED" });
    render(<Leaders />);

    const row = (await screen.findByText("ann@x.dev")).closest("tr")!;
    expect(api.fetchLeaders).toHaveBeenCalledWith("ACTIVE", 0, 25);
    expect(within(row).getByText("2026-10-01 09:30")).toBeInTheDocument();
    await u.click(within(row).getByRole("button", { name: "Suspend" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Closes 3 copy accounts");
    await u.type(within(dialog).getByLabelText("Authenticator code"), "654321");
    expect(within(dialog).getByRole("button", { name: "Suspend" })).toBeDisabled();
    await u.type(within(dialog).getByLabelText(/Reason/), "Wash trading");
    await u.click(within(dialog).getByRole("button", { name: "Suspend" }));

    expect(api.decideLeader).toHaveBeenCalledWith(leader.id, "suspend", "Wash trading", "654321");
    expect(await screen.findByRole("status")).toHaveTextContent("Ann Trades suspended.");
    expect(api.fetchLeaders).toHaveBeenCalledTimes(2);
  });

  it("a suspended leader can only be reinstated, and no reason is asked", async () => {
    const u = userEvent.setup();
    nav.query = "status=SUSPENDED";
    api.fetchLeaders.mockResolvedValue(page({ ...leader, status: "SUSPENDED", reviewReason: "Wash trading" }));
    api.decideLeader.mockResolvedValue({ ...leader, status: "ACTIVE" });
    render(<Leaders />);

    const row = (await screen.findByText("ann@x.dev")).closest("tr")!;
    expect(within(row).getByText("Wash trading")).toBeInTheDocument();
    await u.click(within(row).getByRole("button", { name: "Reinstate" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByLabelText(/Reason/)).toBeNull();
    await u.type(within(dialog).getByLabelText("Authenticator code"), "111222");
    await u.click(within(dialog).getByRole("button", { name: "Reinstate" }));
    expect(api.decideLeader).toHaveBeenCalledWith(leader.id, "reinstate", null, "111222");
  });

  it("a VIEWER sees the list and no way to decide it", async () => {
    session.role = "VIEWER";
    render(<Leaders />);
    await screen.findByText("ann@x.dev");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("keeps the tab in the URL", async () => {
    const u = userEvent.setup();
    api.fetchLeaders.mockResolvedValue(page());
    render(<Leaders />);
    expect(await screen.findByText("Nothing here.")).toBeInTheDocument();
    await u.click(screen.getByRole("tab", { name: "Suspended" }));
    expect(nav.replace).toHaveBeenCalledWith("/copy/leaders?status=SUSPENDED");
  });

  it("gives a leader a profit share of their own with a code, and an empty field puts them back on the default", async () => {
    const u = userEvent.setup();
    // First the default, then -- once saved and reloaded -- the leader's own rate.
    api.fetchLeaders.mockResolvedValueOnce(page({ ...leader, status: "ACTIVE", profitShareBps: null }))
      .mockResolvedValue(page({ ...leader, status: "ACTIVE", profitShareBps: 1500 }));
    api.setLeaderProfitShare.mockResolvedValue({ ...leader, status: "ACTIVE", profitShareBps: 1500 });
    render(<Leaders />);

    const row = (await screen.findByText("Default")).closest("tr")!;
    await u.click(within(row).getByRole("button", { name: "Profit share" }));
    let dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByRole("textbox", { name: "Profit share" }), "15");
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();   // no code yet
    await u.type(within(dialog).getByLabelText("Authenticator code"), "123456");
    await u.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(api.setLeaderProfitShare).toHaveBeenCalledWith(leader.id, 1500, "123456");

    await u.click(within((await screen.findByText("15%")).closest("tr")!).getByRole("button", { name: "Profit share" }));
    dialog = screen.getByRole("dialog");
    await u.clear(within(dialog).getByRole("textbox", { name: "Profit share" }));
    await u.type(within(dialog).getByLabelText("Authenticator code"), "654321");
    await u.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(api.setLeaderProfitShare).toHaveBeenLastCalledWith(leader.id, null, "654321");
  });
});
