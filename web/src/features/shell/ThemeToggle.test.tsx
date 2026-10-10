import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { ThemeToggle } from "./ThemeToggle";

afterEach(() => { delete document.documentElement.dataset.theme; localStorage.clear(); });

describe("ThemeToggle", () => {
  it("flips the attribute the tokens key on, and remembers it", async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);

    await user.click(await screen.findByRole("button", { name: /switch to light/i }));
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(localStorage.getItem("theme")).toBe("light");

    await user.click(screen.getByRole("button", { name: /switch to dark/i }));
    // Dark is the absence of the attribute, not a value -- that is what :root styles.
    expect(document.documentElement.dataset.theme).toBeUndefined();
    expect(localStorage.getItem("theme")).toBe("dark");
  });
});
