import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), path: "/users/7" }));
const session = vi.hoisted(() => ({ role: "SUPERADMIN" as "VIEWER" | "OPERATOR" | "SUPERADMIN" }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace }),
  usePathname: () => nav.path,
}));
vi.mock("@/lib/session", () => ({
  useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }),
  logout: vi.fn(),
}));
const { Shell } = await import("./Shell");

beforeEach(() => { nav.path = "/users/7"; session.role = "SUPERADMIN"; });
afterEach(() => vi.clearAllMocks());

describe("Shell", () => {
  it("marks where you are, and a deeper page links back up its breadcrumb", () => {
    render(<Shell><p>file</p></Shell>);
    expect(screen.getByRole("link", { name: "Customers", current: "page" })).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(crumbs).toHaveTextContent("People/Customers");
    expect(crumbs.querySelector("a")).toHaveAttribute("href", "/users");
  });

  it("jumps straight to a customer file by id, and searches by anything else", async () => {
    const user = userEvent.setup();
    render(<Shell><p /></Shell>);
    const box = screen.getByRole("textbox", { name: "Find a customer" });

    await user.type(box, "412{Enter}");
    expect(nav.push).toHaveBeenLastCalledWith("/users/412");

    await user.type(box, "ann@x.dev{Enter}");
    expect(nav.push).toHaveBeenLastCalledWith("/users?q=ann%40x.dev");
  });

  it("'/' focuses the jump box from anywhere, but not while typing in another field", async () => {
    const user = userEvent.setup();
    render(<Shell><input aria-label="other" /></Shell>);

    await user.keyboard("/");
    expect(screen.getByRole("textbox", { name: "Find a customer" })).toHaveFocus();

    await user.click(screen.getByRole("textbox", { name: "other" }));
    await user.keyboard("/");
    expect(screen.getByRole("textbox", { name: "other" })).toHaveFocus();
    expect(screen.getByRole("textbox", { name: "other" })).toHaveValue("/");
  });

  it("shows a VIEWER only what a VIEWER may open", () => {
    session.role = "VIEWER";
    render(<Shell><p /></Shell>);
    expect(screen.queryByRole("link", { name: "Admins" })).toBeNull();
    expect(screen.getByRole("link", { name: "Audit log" })).toBeInTheDocument();
  });

  it("on a phone, opens the sections as a drawer that a link or Escape closes", async () => {
    const user = userEvent.setup();
    render(<Shell><p /></Shell>);
    expect(screen.getAllByRole("navigation", { name: "Sections" })).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Open menu" }));
    const [, drawer] = screen.getAllByRole("navigation", { name: "Sections" });
    await user.click(within(drawer).getByRole("link", { name: "Treasury" }));
    expect(screen.getAllByRole("navigation", { name: "Sections" })).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Open menu" }));
    await user.keyboard("{Escape}");
    expect(screen.getAllByRole("navigation", { name: "Sections" })).toHaveLength(1);
  });
});
