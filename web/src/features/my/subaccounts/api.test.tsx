import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  fetchSubAccounts: vi.fn(), fetchAllSubAccountApiKeys: vi.fn(), createSubAccountApiKey: vi.fn(),
  revokeSubAccountApiKey: vi.fn(), fetchKyc: vi.fn(), fetchMfa: vi.fn(),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }), useSearchParams: () => new URLSearchParams("") }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { SubApi } = await import("./Api");

const SUBS = [
  { id: 2, email: "ann+sub-a@x.dev", label: "arb bot", frozen: false, createdAt: "2026-09-01T00:00:00Z" },
  { id: 3, email: "ann+sub-b@x.dev", label: "grid bot", frozen: true, createdAt: "2026-09-02T00:00:00Z" },
];
const key = (over: Partial<Record<string, unknown>> = {}) => ({
  id: "01a0cf7b-0000-7000-8000-000000000010", apiKey: "ak_abcdef123456789", label: "reader", canRead: true, canTrade: false, canWithdraw: false,
  ipAllowlist: [], lastUsedAt: null, expiresAt: null, createdAt: "2026-09-01T00:00:00Z",
  revokedAt: null, revokedReason: null, ...over,
});
const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  api.fetchSubAccounts.mockResolvedValue(SUBS);
  api.fetchKyc.mockResolvedValue({ status: "APPROVED", level: "INTERMEDIATE", submittedAt: null, reviewedAt: null, expiresAt: null, rejectionReason: null });
  api.fetchMfa.mockResolvedValue({ enabled: true, recoveryCodesRemaining: 8, enabledAt: "2026-01-01T00:00:00Z" });
  api.fetchAllSubAccountApiKeys.mockResolvedValue({
    2: [key(), key({ id: "01a0cf7b-0000-7000-8000-000000000011", apiKey: "ak_zzz999888777", label: "trader", canTrade: true, ipAllowlist: ["10.0.0.1"] })],
    3: [key({ id: "01a0cf7b-0000-7000-8000-000000000012", apiKey: "ak_frozen0000000", label: "old" })],
  });
});
afterEach(() => vi.clearAllMocks());

describe("Sub-account API management", () => {
  it("lists every sub's keys, masked, with what each may do", async () => {
    withClient(<SubApi />);

    const trader = (await screen.findByRole("cell", { name: "trader" })).closest("tr")!;
    expect(within(trader).getByText("arb bot")).toBeInTheDocument();
    expect(within(trader).getByText("ak_zzz…8777")).toBeInTheDocument();
    expect(within(trader).getAllByText("Allowed")).toHaveLength(2); // read and trade
    expect(within(trader).getByText("1 addresses")).toBeInTheDocument();

    const reader = screen.getByRole("cell", { name: "reader" }).closest("tr")!;
    expect(within(reader).getByText("Not allowed")).toBeInTheDocument(); // trade
    expect(within(reader).getByText("Any address")).toBeInTheDocument();

    expect(within(trader).getByText("Active")).toBeInTheDocument();

    // A frozen sub's keys are still listed -- they read and cancel -- and say whose they are:
    // an order refused by the trading gate is explained by this column and nothing else.
    const old = screen.getByRole("cell", { name: "old" }).closest("tr")!;
    expect(within(old).getByText("Frozen")).toBeInTheDocument();
    expect(screen.getByText("API keys (3)")).toBeInTheDocument();
  });

  it("filters on Search by sub and by label, not as the fields change", async () => {
    const user = userEvent.setup();
    withClient(<SubApi />);
    await screen.findByRole("cell", { name: "trader" });

    await user.type(screen.getByRole("textbox", { name: "Label" }), "read");
    expect(screen.getByRole("cell", { name: "trader" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(screen.queryByRole("cell", { name: "trader" })).toBeNull();
    expect(screen.getByRole("cell", { name: "reader" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Reset" }));
    expect(screen.getByRole("cell", { name: "trader" })).toBeInTheDocument();

    await user.selectOptions(screen.getByRole("combobox", { name: "Sub-account" }), "3");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(screen.getByRole("cell", { name: "old" })).toBeInTheDocument();
    expect(screen.queryByRole("cell", { name: "reader" })).toBeNull();
  });

  it("offers only unfrozen subs to create a key for", async () => {
    const user = userEvent.setup();
    withClient(<SubApi />);

    await user.click(await screen.findByRole("button", { name: "+ Create API key" }));
    const picker = screen.getAllByRole("combobox", { name: "Sub-account" })[1];
    expect(within(picker).getAllByRole("option").map((o) => o.textContent)).toEqual(["arb bot"]);
  });

  it("with 2FA off, says so and refuses to create", async () => {
    api.fetchMfa.mockResolvedValue({ enabled: false, recoveryCodesRemaining: 0, enabledAt: null });
    withClient(<SubApi />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Turn on two-factor authentication first.");
    expect(screen.getByRole("button", { name: "+ Create API key" })).toBeDisabled();
  });

  it("revokes against the sub that owns the key, once confirmed", async () => {
    const user = userEvent.setup();
    api.revokeSubAccountApiKey.mockResolvedValue(undefined);
    withClient(<SubApi />);

    const trader = (await screen.findByRole("cell", { name: "trader" })).closest("tr")!;
    await user.click(within(trader).getByRole("button", { name: "Delete" }));
    expect(api.revokeSubAccountApiKey).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm" }));
    expect(api.revokeSubAccountApiKey.mock.calls[0][0]).toEqual({ id: 2, keyId: "01a0cf7b-0000-7000-8000-000000000011" });
  });
});
