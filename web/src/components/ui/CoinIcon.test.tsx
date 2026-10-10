import { render } from "@testing-library/react";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { COIN_ICONS, CoinIcon } from "./CoinIcon";

describe("CoinIcon", () => {
  it("renders the file for a known symbol, case-insensitively", () => {
    const { container } = render(<CoinIcon symbol="btc" />);
    expect(container.querySelector("img")).toHaveAttribute("src", "/icons/coins/btc.svg");
  });

  it("falls back to a letter, never a broken image, for an unknown symbol", () => {
    const { container } = render(<CoinIcon symbol="PEPE" />);
    expect(container.querySelector("img")).toBeNull();
    expect(container).toHaveTextContent("P");
  });

  it("lists exactly the files that exist", () => {
    // A symbol in the set without a file is a broken image; a file not in the set is never shown.
    const files = readdirSync(join(process.cwd(), "public/icons/coins"))
      .filter((f) => f.endsWith(".svg")).map((f) => f.replace(".svg", "").toUpperCase()).sort();
    expect(files).toEqual([...COIN_ICONS].sort());
  });
});
