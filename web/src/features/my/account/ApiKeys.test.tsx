import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn() }));
const api = vi.hoisted(() => ({ fetchKyc: vi.fn(), fetchMfa: vi.fn(), fetchApiKeys: vi.fn(), createApiKey: vi.fn(), revokeApiKey: vi.fn(), updateApiKey: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: nav.replace }) }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { ApiKeys } = await import("./ApiKeys");
const { CreateApiKey } = await import("./CreateApiKey");

const withClient = (ui: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
};
beforeEach(() => {
  api.fetchKyc.mockResolvedValue({ status: "APPROVED", level: "INTERMEDIATE", submittedAt: null, reviewedAt: null, expiresAt: null, rejectionReason: null });
  api.fetchMfa.mockResolvedValue({ enabled: true, recoveryCodesRemaining: 0, enabledAt: null });
  api.fetchApiKeys.mockResolvedValue([]);
});
afterEach(() => { vi.clearAllMocks(); });

describe("API Management", () => {
  it("sends Create to its own page", async () => {
    withClient(<ApiKeys />);
    expect(await screen.findByRole("link", { name: "Create API key" })).toHaveAttribute("href", "/en/my/account/api/create");
  });

  it("keeps Create off, and says why, until the account may hold a key", async () => {
    api.fetchKyc.mockResolvedValue({ status: "APPROVED", level: "BASIC", submittedAt: null, reviewedAt: null, expiresAt: null, rejectionReason: null });
    withClient(<ApiKeys />);
    expect(await screen.findByText(/Verified Plus/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create API key" })).toBeDisabled();
  });
});

describe("Create API key", () => {
  it("asks for the authenticator code in a pop-up, then shows the secret once", async () => {
    const user = userEvent.setup();
    api.createApiKey.mockResolvedValue({ id: "01a0cf7b-0000-7000-8000-000000000001", apiKey: "KEY", secret: "SECRET", label: "bot", canRead: true, canTrade: false, canWithdraw: false });
    withClient(<CreateApiKey />);

    await user.type(await screen.findByLabelText("Label"), "bot");
    expect(screen.queryByRole("group", { name: "Authenticator code" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "Create API key" }));

    const dialog = screen.getByRole("dialog", { name: "Authenticator code" });
    expect(within(dialog).getByRole("group", { name: "Authenticator code" })).toBeInTheDocument();
    await user.keyboard("123456");
    await waitFor(() => expect(api.createApiKey).toHaveBeenCalled());
    expect(api.createApiKey.mock.calls[0][0]).toMatchObject({ label: "bot", mfaCode: "123456", canRead: true });

    const shown = await screen.findByRole("dialog", { name: "Your new API key" });
    expect(within(shown).getByText("SECRET")).toBeInTheDocument();
    await user.click(within(shown).getByRole("button", { name: "I've saved it" }));
    expect(nav.replace).toHaveBeenCalledWith("/en/my/account/api");
  });
});

describe("A key's card", () => {
  const key = {
    id: "01a0cf7b-0000-7000-8000-000000000007", apiKey: "ak_abcdefghijklmnopqrstuvwxyz", label: "arb bot", canRead: true, canTrade: true, canWithdraw: false,
    ipAllowlist: ["10.0.0.0/8"], lastUsedAt: null, expiresAt: null, createdAt: "2026-09-01T00:00:00Z",
    revokedAt: null, revokedReason: null,
  };

  it("shows the label, the status and the masked key, and nothing else", async () => {
    api.fetchApiKeys.mockResolvedValue([key]);
    withClient(<ApiKeys />);

    const card = await screen.findByRole("article", { name: "arb bot" });
    expect(within(card).getByText("Active")).toBeInTheDocument();
    expect(within(card).queryByText("Read · Spot trading")).toBeNull();
    expect(within(card).queryByText("10.0.0.0/8")).toBeNull();
    expect(within(card).queryByText(key.apiKey)).toBeNull();
  });

  it("deletes from an icon button, still behind a confirmation", async () => {
    const user = userEvent.setup();
    api.fetchApiKeys.mockResolvedValue([key]);
    api.revokeApiKey.mockResolvedValue(undefined);
    withClient(<ApiKeys />);

    const card = await screen.findByRole("article", { name: "arb bot" });
    const remove = within(card).getByRole("button", { name: "Delete" });
    expect(remove.textContent).toBe("");
    await user.click(remove);
    expect(api.revokeApiKey).not.toHaveBeenCalled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(api.revokeApiKey.mock.calls[0][0]).toBe("01a0cf7b-0000-7000-8000-000000000007"));
  });

  it("edits in place, sets the expiry afresh, and asks for the code before saving", async () => {
    const user = userEvent.setup();
    api.fetchApiKeys.mockResolvedValue([{ ...key, expiresAt: "2026-12-01T00:00:00Z" }]);
    api.updateApiKey.mockResolvedValue({ ...key, label: "renamed" });
    withClient(<ApiKeys />);

    const card = await screen.findByRole("article", { name: "arb bot" });
    await user.click(within(card).getByRole("button", { name: "Edit" }));
    const form = within(card).getByRole("form", { name: "Edit API key" });
    await user.clear(within(form).getByLabelText("Label"));
    await user.type(within(form).getByLabelText("Label"), "renamed");
    await user.click(within(form).getByLabelText("Spot trading"));
    await user.click(within(form).getByRole("button", { name: "Save changes" }));
    expect(api.updateApiKey).not.toHaveBeenCalled();

    await user.keyboard("654321");
    await waitFor(() => expect(api.updateApiKey).toHaveBeenCalled());
    const sent = api.updateApiKey.mock.calls[0][0];
    expect(sent).toMatchObject({ id: "01a0cf7b-0000-7000-8000-000000000007", label: "renamed", canTrade: false, ipAllowlist: ["10.0.0.0/8"], mfaCode: "654321" });
    // "In 90 days" is the default for a key that expires: from now, not the old date carried over.
    expect(new Date(sent.expiresAt).getTime()).toBeGreaterThan(Date.now() + 89 * 86_400_000);
  });
});
