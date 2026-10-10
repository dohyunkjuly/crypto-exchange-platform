import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/lib/i18n";
import en from "@/lib/i18n/messages/en.json";
import { ToastProvider, useToast } from "./Toast";

function Fire({ times = 1 }: { times?: number }) {
  const show = useToast();
  return (
    <button onClick={() => { for (let i = 0; i < times; i++) show({ tone: "info", title: `Order ${i}`, body: "BTCUSDT" }); }}>
      fire
    </button>
  );
}
const renderToasts = (times?: number) =>
  render(
    <I18nProvider locale="en" messages={en}>
      <ToastProvider><Fire times={times} /></ToastProvider>
    </I18nProvider>,
  );

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
afterEach(() => vi.useRealTimers());

describe("ToastProvider", () => {
  it("shows what it was given and clears itself without anyone pressing anything", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderToasts();

    await user.click(screen.getByRole("button", { name: "fire" }));
    expect(screen.getByRole("status")).toHaveTextContent("Order 0");
    expect(screen.getByRole("status")).toHaveTextContent("BTCUSDT");

    await act(async () => { vi.advanceTimersByTime(6_000); });
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("can be dismissed early", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderToasts();

    await user.click(screen.getByRole("button", { name: "fire" }));
    await user.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("status")).toBeNull();
  });

  /** A burst of fills must not become a wall of cards covering the screen. */
  it("keeps only the newest few", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderToasts(7);

    await user.click(screen.getByRole("button", { name: "fire" }));

    const shown = screen.getAllByRole("status");
    expect(shown).toHaveLength(4);
    expect(shown[shown.length - 1]).toHaveTextContent("Order 6");
  });
});
