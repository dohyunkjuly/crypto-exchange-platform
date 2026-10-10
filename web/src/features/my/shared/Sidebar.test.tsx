import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ pathname: "/en/my/dashboard" }));
vi.mock("next/navigation", () => ({ usePathname: () => nav.pathname }));
const { Sidebar } = await import("./Sidebar");

afterEach(() => { nav.pathname = "/en/my/dashboard"; });
// The rail is the second block; the first is the phone's dropdown.
const rail = () => document.querySelector("nav > div:nth-of-type(2)") as HTMLElement;
const phone = () => document.querySelector("nav > div:nth-of-type(1)") as HTMLElement;

describe("Sidebar", () => {
  it("keeps Account collapsed outside the account pages, and toggles on click", async () => {
    const u = userEvent.setup();
    render(<Sidebar />);
    const toggle = within(rail()).getByRole("button", { name: "Account" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(within(rail()).queryByRole("link", { name: "Security" })).toBeNull();

    await u.click(toggle);
    expect(within(rail()).getByRole("link", { name: "Security" })).toBeInTheDocument();
    await u.click(toggle);
    expect(within(rail()).queryByRole("link", { name: "Security" })).toBeNull();
  });

  it("opens itself when the current page is inside Account", () => {
    nav.pathname = "/en/my/account/security";
    render(<Sidebar />);
    expect(within(rail()).getByRole("button", { name: "Account" })).toHaveAttribute("aria-expanded", "true");
    expect(within(rail()).getByRole("link", { name: "Security" })).toHaveAttribute("aria-current", "page");
  });

  it("keeps its section lit on a page inside it", () => {
    nav.pathname = "/en/my/account/security/password";
    render(<Sidebar />);
    expect(within(rail()).getByRole("button", { name: "Account" })).toHaveAttribute("aria-expanded", "true");
    expect(within(rail()).getByRole("link", { name: "Security" })).toHaveAttribute("aria-current", "page");
  });

  it("on a phone names the page you are on, and opens the whole navigation under it", async () => {
    const u = userEvent.setup();
    nav.pathname = "/en/my/sub-accounts/assets";
    render(<Sidebar />);

    // Closed: the button says where you are, and only the rail's copy of a link is in the tree.
    const toggle = within(phone()).getByRole("button", { name: "Asset management" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.getAllByRole("link", { name: "Dashboard" })).toHaveLength(1);

    await u.click(toggle);
    const sheet = document.getElementById("account-menu")!;
    expect(within(sheet).getByRole("link", { name: "Dashboard" })).toBeInTheDocument();

    // The group you are in is open; the other is a heading until asked, as on the rail.
    expect(within(sheet).getByRole("link", { name: "Asset management" })).toBeInTheDocument();
    expect(within(sheet).queryByRole("link", { name: "Security" })).toBeNull();
    await u.click(within(sheet).getByRole("button", { name: "Account" }));
    expect(within(sheet).getByRole("link", { name: "Security" })).toBeInTheDocument();

    // Choosing a page closes it: the page behind has changed.
    await u.click(within(sheet).getByRole("link", { name: "Dashboard" }));
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("has Sub-accounts as a group of its own, open on its pages, with the list lit only on itself", () => {
    nav.pathname = "/en/my/sub-accounts/orders";
    render(<Sidebar />);
    expect(within(rail()).getByRole("button", { name: "Account" })).toHaveAttribute("aria-expanded", "false");
    expect(within(rail()).getByRole("button", { name: "Sub-accounts" })).toHaveAttribute("aria-expanded", "true");
    expect(within(rail()).getByRole("link", { name: "Order management" })).toHaveAttribute("aria-current", "page");
    expect(within(rail()).getByRole("link", { name: "Account management" })).not.toHaveAttribute("aria-current");
  });
});
