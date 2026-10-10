import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchDashboard: vi.fn() }));
const audit = vi.hoisted(() => ({ fetchAudit: vi.fn() }));
const treasury = vi.hoisted(() => ({ fetchRevenue: vi.fn() }));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/features/audit/api", async (orig) => ({ ...(await orig<object>()), ...audit }));
vi.mock("@/features/treasury/api", async (orig) => ({ ...(await orig<object>()), ...treasury }));
const { Dashboard } = await import("./Dashboard");

beforeEach(() => {
  api.fetchDashboard.mockResolvedValue({
    asOf: "2026-09-23T14:00:00Z",
    signups: { today: "12", yesterday: "10" },
    trades: { today: "80", yesterday: "100" },
    volume: { today: "250000.5", yesterday: "0" },
    fees: { today: "0", yesterday: "0" },
    pairs: [{ symbol: "BTCUSDT", quote: "USDT", volumeToday: "250000.5", volumeYesterday: "200000", tradesToday: 80 }],
  });
  audit.fetchAudit.mockResolvedValue({ items: [
    { id: 1, adminId: 1, adminEmail: "ops@x.dev", action: "USER_FROZEN", targetType: "user", targetId: "42",
      before: null, after: null, ipAddress: "10.0.0.1", createdAt: "2026-09-23T13:05:00Z" },
  ], total: 1, page: 0, size: 8 });
  treasury.fetchRevenue.mockImplementation((_a: string, days: number) =>
    Promise.resolve(Array.from({ length: days }, (_, i) => ({ day: `2026-09-${String(i + 1).padStart(2, "0")}`, fees: "1", rebates: "0" }))));
});
afterEach(() => vi.clearAllMocks());

describe("Dashboard", () => {
  it("compares today with yesterday up to the same time, and says nothing when there is no yesterday", async () => {
    render(<Dashboard />);
    expect(await screen.findByText("↑ 20.0% vs same time yesterday")).toBeInTheDocument();   // signups 12 vs 10
    expect(screen.getByText("↓ 20.0% vs same time yesterday")).toBeInTheDocument();          // trades 80 vs 100
    // Volume and fees had nothing yesterday: no line under them at all.
    expect(screen.getAllByText(/vs same time yesterday/)).toHaveLength(2);
    expect(screen.queryByText(/none/)).not.toBeInTheDocument();
    expect(screen.getAllByText("250,000.50")).toHaveLength(2);   // the tile, and the one pair that traded
  });

  it("lists the pairs that traded and what staff did, linking a customer target", async () => {
    render(<Dashboard />);
    const pair = (await screen.findByText("BTCUSDT")).closest("tr")!;
    expect(within(pair).getByText("↑ 25.0%")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "user 42" })).toHaveAttribute("href", "/users/42");
  });

  it("shows the five busiest pairs and keeps five rows however many traded", async () => {
    const pair = (symbol: string, volume: string) =>
      ({ symbol, quote: "USDT", volumeToday: volume, volumeYesterday: "0", tradesToday: 1 });
    api.fetchDashboard.mockResolvedValue({
      asOf: "2026-09-23T14:00:00Z",
      signups: { today: "0", yesterday: "0" }, trades: { today: "0", yesterday: "0" },
      volume: { today: "0", yesterday: "0" }, fees: { today: "0", yesterday: "0" },
      pairs: [pair("A", "1"), pair("B", "7"), pair("C", "3"), pair("D", "9"), pair("E", "5"), pair("F", "2"), pair("G", "8")],
    });
    const { unmount } = render(<Dashboard />);
    const rows = within((await screen.findByText("D")).closest("table")!).getAllByRole("row").slice(1);
    expect(rows.map((r) => r.textContent?.trim().slice(0, 1))).toEqual(["D", "G", "B", "E", "C"]);
    unmount();

    api.fetchDashboard.mockResolvedValue({
      asOf: "2026-09-23T14:00:00Z",
      signups: { today: "0", yesterday: "0" }, trades: { today: "0", yesterday: "0" },
      volume: { today: "0", yesterday: "0" }, fees: { today: "0", yesterday: "0" },
      pairs: [pair("A", "1")],
    });
    const second = render(<Dashboard />);
    const table = (await screen.findByText("A")).closest("table")!;
    expect(within(table).getAllByRole("row")).toHaveLength(6);   // the header and five rows, four of them blank
    second.unmount();

    api.fetchDashboard.mockResolvedValue({
      asOf: "2026-09-23T14:00:00Z",
      signups: { today: "0", yesterday: "0" }, trades: { today: "0", yesterday: "0" },
      volume: { today: "0", yesterday: "0" }, fees: { today: "0", yesterday: "0" },
      pairs: [],
    });
    render(<Dashboard />);
    const quiet = (await screen.findByText("Nothing has traded today.")).closest("table")!;
    expect(within(quiet).getAllByRole("row")).toHaveLength(6);   // still five rows, the first one saying why
  });

  it("charts USDT fees and changes the range without refetching the rest", async () => {
    const user = userEvent.setup();
    render(<Dashboard />);
    await screen.findByText("BTCUSDT");
    expect(treasury.fetchRevenue).toHaveBeenLastCalledWith("USDT", 30);

    await user.click(screen.getByRole("tab", { name: "7d" }));
    expect(treasury.fetchRevenue).toHaveBeenLastCalledWith("USDT", 7);
    expect(api.fetchDashboard).toHaveBeenCalledTimes(1);
  });
});
