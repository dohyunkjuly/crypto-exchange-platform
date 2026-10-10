import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ fetchAdmins: vi.fn(), createAdmin: vi.fn(), changeRole: vi.fn(), disableAdmin: vi.fn(), resetTotp: vi.fn() }));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "root@x.dev", role: "SUPERADMIN" }) }));
const { Admins } = await import("./Admins");

const me = { id: 1, email: "root@x.dev", role: "SUPERADMIN", totpEnrolled: true, disabledAt: null, createdAt: "2026-09-01T00:00:00Z" };
const ops = { id: 2, email: "ops@x.dev", role: "OPERATOR", totpEnrolled: true, disabledAt: null, createdAt: "2026-09-02T00:00:00Z" };

beforeEach(() => { api.fetchAdmins.mockResolvedValue([me, ops]); });
afterEach(() => vi.clearAllMocks());

describe("Admins", () => {
  it("offers nothing on your own row, and everything on a colleague's", async () => {
    render(<Admins />);
    const mine = (await screen.findByText("root@x.dev")).closest("tr")!;
    expect(within(mine).queryByRole("button")).toBeNull();
    expect(within(mine).queryByRole("combobox")).toBeNull();

    const theirs = screen.getByText("ops@x.dev").closest("tr")!;
    expect(within(theirs).getByRole("combobox", { name: "Role of ops@x.dev" })).toBeInTheDocument();
    expect(within(theirs).getByRole("button", { name: "Reset authenticator" })).toBeInTheDocument();
    expect(within(theirs).getByRole("button", { name: "Disable" })).toBeInTheDocument();
  });

  it("adds a colleague only with a long enough password and your code", async () => {
    const u = userEvent.setup();
    api.createAdmin.mockResolvedValue({ ...ops, id: 3, email: "new@x.dev", role: "VIEWER", totpEnrolled: false });
    render(<Admins />);
    await screen.findByText("ops@x.dev");

    await u.click(screen.getByRole("button", { name: "Add admin" }));
    const dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByLabelText("Email"), "new@x.dev");
    await u.type(within(dialog).getByLabelText(/First password/), "short");
    await u.type(within(dialog).getByLabelText("Your authenticator code"), "123456");
    expect(within(dialog).getByRole("button", { name: "Add" })).toBeDisabled();
    await u.type(within(dialog).getByLabelText(/First password/), "-but-now-long-enough");
    await u.click(within(dialog).getByRole("button", { name: "Add" }));

    expect(api.createAdmin).toHaveBeenCalledWith("new@x.dev", "short-but-now-long-enough", "VIEWER", "123456");
    expect(await screen.findByRole("status")).toHaveTextContent("enrol an authenticator at first login");
  });

  it("resets an authenticator behind a code, and re-reads the list", async () => {
    const u = userEvent.setup();
    api.resetTotp.mockResolvedValue({ ...ops, totpEnrolled: false });
    render(<Admins />);
    await screen.findByText("ops@x.dev");

    await u.click(screen.getByRole("button", { name: "Reset authenticator" }));
    await u.type(within(screen.getByRole("dialog")).getByLabelText("Your authenticator code"), "654321");
    await u.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Reset" }));
    expect(api.resetTotp).toHaveBeenCalledWith(2, "654321");
    expect(api.fetchAdmins).toHaveBeenCalledTimes(2);
  });
});
