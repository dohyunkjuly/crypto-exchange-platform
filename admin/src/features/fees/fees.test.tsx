import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchFees: vi.fn(), changeLevel: vi.fn(), addLevel: vi.fn() }));
const session = vi.hoisted(() => ({ role: "OPERATOR" }));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
const { Fees } = await import("./Fees");

const level = (n: number, over: Partial<Record<string, unknown>> = {}) => ({
  level: n, name: `VIP ${n}`, min30dVolume: n === 0 ? "0" : `${n}000000`, makerFeeBps: 10 - n, takerFeeBps: 10,
  updatedAt: "2026-09-01T00:00:00Z", ...over,
});
const code = "123456";

beforeEach(() => {
  session.role = "OPERATOR";
  api.fetchFees.mockResolvedValue([level(0), level(1), level(2, { makerFeeBps: -1 })]);
});
afterEach(() => vi.clearAllMocks());

describe("Fees", () => {
  it("shows each level's volume and rates as bps and percent", async () => {
    render(<Fees />);
    const vip1 = (await screen.findByText("VIP 1")).closest("tr")!;
    expect(within(vip1).getByText("1,000,000")).toBeInTheDocument();
    expect(within(vip1).getByText("bps · 0.09%")).toBeInTheDocument();
    expect(within(screen.getByText("VIP 2").closest("tr")!).getByText("bps · -0.01%")).toBeInTheDocument();
  });

  it("edits a level with a code; level 0's volume stays at zero", async () => {
    const u = userEvent.setup();
    api.changeLevel.mockResolvedValue(level(1, { name: "Silver" }));
    render(<Fees />);
    await u.click(within((await screen.findByText("VIP 1")).closest("tr")!).getByRole("button", { name: "Edit" }));
    const dialog = screen.getByRole("dialog");
    await u.clear(within(dialog).getByRole("textbox", { name: "Name" }));
    await u.type(within(dialog).getByRole("textbox", { name: "Name" }), "Silver");
    await u.clear(within(dialog).getByRole("textbox", { name: "Maker fee" }));
    await u.type(within(dialog).getByRole("textbox", { name: "Maker fee" }), "-2");
    await u.type(within(dialog).getByRole("textbox", { name: /authenticator/i }), code);
    await u.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(api.changeLevel).toHaveBeenCalledWith(1, { name: "Silver", min30dVolume: "1000000", makerFeeBps: -2, takerFeeBps: 10 }, code);
    expect(await screen.findByRole("status")).toHaveTextContent("Silver saved");

    await u.click(within(screen.getByText("VIP 0").closest("tr")!).getByRole("button", { name: "Edit" }));
    expect(within(screen.getByRole("dialog")).getByRole("textbox", { name: "30-day volume" })).toBeDisabled();
  });

  it("adds the next level on top", async () => {
    const u = userEvent.setup();
    api.addLevel.mockResolvedValue(level(3));
    render(<Fees />);
    await u.click(await screen.findByRole("button", { name: "Add level" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("Add level 3");
    for (const [name, value] of [["Name", "VIP 3"], ["30-day volume", "5000000"], ["Maker fee", "6"], ["Taker fee", "8"]]) {
      await u.type(within(dialog).getByRole("textbox", { name }), value);
    }
    await u.type(within(dialog).getByRole("textbox", { name: /authenticator/i }), code);
    await u.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(api.addLevel).toHaveBeenCalledWith({ name: "VIP 3", min30dVolume: "5000000", makerFeeBps: 6, takerFeeBps: 8 }, code);
  });

  it("offers a VIEWER nothing to change", async () => {
    session.role = "VIEWER";
    render(<Fees />);
    await screen.findByText("VIP 1");
    expect(screen.queryByRole("button", { name: /Edit|Add level/ })).not.toBeInTheDocument();
  });
});
