import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BffError } from "@/lib/bff/errors";

const api = vi.hoisted(() => ({ fetchMfa: vi.fn(), changePassword: vi.fn() }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { ChangePassword } = await import("./ChangePassword");

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><ChangePassword /></QueryClientProvider>);
};
type User = ReturnType<typeof userEvent.setup>;
const NEW = "a-long-new-password";
const typePasswords = async (user: User, first = NEW, second = NEW) => {
  await user.type(await screen.findByLabelText("New password"), first);
  await user.type(screen.getByLabelText("Confirm password"), second);
};
afterEach(() => { vi.clearAllMocks(); });

describe("ChangePassword", () => {
  it("keeps Confirm off until the password is long enough and typed twice the same", async () => {
    const user = userEvent.setup();
    api.fetchMfa.mockResolvedValue({ enabled: false, recoveryCodesRemaining: 0, enabledAt: null });
    renderPage();

    await typePasswords(user, "short", "short");
    expect(screen.getByRole("button", { name: "Confirm" })).toBeDisabled();
    await user.clear(screen.getByLabelText("New password"));
    await user.clear(screen.getByLabelText("Confirm password"));
    await typePasswords(user, NEW, `${NEW}x`);
    expect(screen.getByText("The passwords don't match.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm" })).toBeDisabled();
  });

  it("with 2FA on, asks for the authenticator code in a pop-up and sends only that", async () => {
    const user = userEvent.setup();
    api.fetchMfa.mockResolvedValue({ enabled: true, recoveryCodesRemaining: 0, enabledAt: "2026-09-01T00:00:00Z" });
    api.changePassword.mockResolvedValue(undefined);
    renderPage();

    await typePasswords(user);
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    expect(screen.getByRole("dialog", { name: "Authenticator code" })).toBeInTheDocument();
    await user.keyboard("123456");

    await waitFor(() => expect(api.changePassword).toHaveBeenCalled());
    expect(api.changePassword.mock.calls[0][0]).toEqual({ newPassword: NEW, mfaCode: "123456" });
    expect(await screen.findByRole("status")).toHaveTextContent("Password changed");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("with 2FA off, asks for the current password instead", async () => {
    const user = userEvent.setup();
    api.fetchMfa.mockResolvedValue({ enabled: false, recoveryCodesRemaining: 0, enabledAt: null });
    api.changePassword.mockResolvedValue(undefined);
    renderPage();

    await typePasswords(user);
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    const dialog = screen.getByRole("dialog", { name: "Current password" });
    await user.type(within(dialog).getByLabelText("Current password"), "the-old-password");
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));

    await waitFor(() => expect(api.changePassword).toHaveBeenCalled());
    expect(api.changePassword.mock.calls[0][0]).toEqual({ newPassword: NEW, currentPassword: "the-old-password" });
  });

  it("shows a refused proof inside the pop-up", async () => {
    const user = userEvent.setup();
    api.fetchMfa.mockResolvedValue({ enabled: false, recoveryCodesRemaining: 0, enabledAt: null });
    api.changePassword.mockRejectedValue(new BffError(2024, 400, "x"));
    renderPage();

    await typePasswords(user);
    await user.click(screen.getByRole("button", { name: "Confirm" }));
    const dialog = screen.getByRole("dialog", { name: "Current password" });
    await user.type(within(dialog).getByLabelText("Current password"), "a-wrong-password");
    await user.click(within(dialog).getByRole("button", { name: "Confirm" }));

    expect(await within(dialog).findByText("That's not your current password.")).toBeInTheDocument();
  });
});
