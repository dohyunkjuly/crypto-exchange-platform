import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CodeInput } from "./CodeInput";

const box = (n: number) => screen.getByLabelText(`Digit ${n} of 6`) as HTMLInputElement;
const hidden = () => document.querySelector('input[name="code"]') as HTMLInputElement;

describe("CodeInput", () => {
  it("advances as digits are typed and exposes the joined value", async () => {
    const user = userEvent.setup();
    const onComplete = vi.fn();
    render(<CodeInput autoFocus onComplete={onComplete} />);

    await user.keyboard("123456");

    expect([1, 2, 3, 4, 5, 6].map((n) => box(n).value)).toEqual(["1", "2", "3", "4", "5", "6"]);
    expect(hidden().value).toBe("123456");
    expect(onComplete).toHaveBeenCalledExactlyOnceWith("123456");
  });

  it("fills every box from a paste, which is how most codes arrive", async () => {
    const user = userEvent.setup();
    const onComplete = vi.fn();
    render(<CodeInput autoFocus onComplete={onComplete} />);

    await user.paste("482913");

    expect(hidden().value).toBe("482913");
    expect(onComplete).toHaveBeenCalledExactlyOnceWith("482913");
  });

  it("ignores anything that is not a digit, including pasted separators", async () => {
    const user = userEvent.setup();
    render(<CodeInput autoFocus />);

    await user.paste("48-29 13");

    expect(hidden().value).toBe("482913");
  });

  it("backspace on an empty box clears and moves to the previous one", async () => {
    const user = userEvent.setup();
    render(<CodeInput autoFocus />);
    await user.keyboard("12");
    // Focus is on box 3 (empty) after typing two digits.
    await user.keyboard("{Backspace}");

    expect(box(2).value).toBe("");
    expect(document.activeElement).toBe(box(2));
    expect(hidden().value).toBe("1");
  });

  it("does not report completion until all six are present", async () => {
    const user = userEvent.setup();
    const onComplete = vi.fn();
    render(<CodeInput autoFocus onComplete={onComplete} />);

    await user.keyboard("12345");

    expect(onComplete).not.toHaveBeenCalled();
  });
});
