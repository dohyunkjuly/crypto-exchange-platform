import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ pathname: "/en/login", replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  usePathname: () => nav.pathname,
  useRouter: () => ({ replace: nav.replace }),
}));
const { LocaleSwitcher } = await import("./LocaleSwitcher");

afterEach(() => { nav.replace.mockReset(); document.cookie = "NEXT_LOCALE=; max-age=0; path=/"; });

describe("LocaleSwitcher", () => {
  it("swaps the locale on the current path and remembers the choice", async () => {
    const user = userEvent.setup();
    render(<LocaleSwitcher />);
    await user.click(screen.getByRole("button", { name: "Language" }));
    await user.click(screen.getByRole("menuitemradio", { name: "한국어" }));

    expect(nav.replace).toHaveBeenCalledWith("/ko/login");
    expect(document.cookie).toContain("NEXT_LOCALE=ko");
  });

  it("closes on Escape and marks the current language", async () => {
    const user = userEvent.setup();
    render(<LocaleSwitcher />);
    await user.click(screen.getByRole("button", { name: "Language" }));
    expect(screen.getByRole("menuitemradio", { name: "English" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
  });
});
