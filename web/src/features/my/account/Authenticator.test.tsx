import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchMfa: vi.fn(), startTotp: vi.fn(), confirmTotp: vi.fn(), disableMfa: vi.fn() }));
vi.mock("../shared/api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { Authenticator } = await import("./Authenticator");

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><Authenticator /></QueryClientProvider>);
};
const OFF = { enabled: false, recoveryCodesRemaining: 0, enabledAt: null };
const ON = { enabled: true, recoveryCodesRemaining: 10, enabledAt: "2021-11-15T09:00:00Z" };
afterEach(() => { vi.clearAllMocks(); });

describe("Authenticator", () => {
  it("sets up with the QR code, the key under it and a code, and never shows recovery codes", async () => {
    const user = userEvent.setup();
    api.fetchMfa.mockResolvedValueOnce(OFF).mockResolvedValue(ON);
    api.startTotp.mockResolvedValue({ secret: "JBSWY3DPEHPK3PXP", provisioningUri: "otpauth://totp/Example:me?secret=JBSWY3DPEHPK3PXP&issuer=Example" });
    api.confirmTotp.mockResolvedValue({ recoveryCodes: ["AAAA-BBBB-CCCC-DDDD"] });
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Enable" }));
    expect(await screen.findByRole("img", { name: "QR code for your authenticator app" })).toBeInTheDocument();
    expect(screen.getByText("JBSWY3DPEHPK3PXP")).toBeInTheDocument();
    await user.keyboard("123456");

    await waitFor(() => expect(api.confirmTotp).toHaveBeenCalled());
    expect(api.confirmTotp.mock.calls[0][0]).toBe("123456");
    expect(await screen.findByText(/^Added: /)).toBeInTheDocument();
    expect(screen.queryByText("AAAA-BBBB-CCCC-DDDD")).toBeNull();
  });

  it("once on, offers delete only, and deleting asks for a current code in a pop-up", async () => {
    const user = userEvent.setup();
    api.fetchMfa.mockResolvedValue(ON);
    api.disableMfa.mockResolvedValue(undefined);
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Remove authenticator" }));
    expect(screen.queryByRole("button", { name: /edit/i })).toBeNull();
    expect(screen.getByRole("dialog", { name: "Remove authenticator" })).toBeInTheDocument();
    await user.keyboard("654321");

    await waitFor(() => expect(api.disableMfa).toHaveBeenCalled());
    expect(api.disableMfa.mock.calls[0][0]).toBe("654321");
  });
});
