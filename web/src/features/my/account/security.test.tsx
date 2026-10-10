import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { create } from "qrcode";
import { QrCode } from "../shared/QrCode";

describe("QrCode", () => {
  it("draws every dark module of the code, inside a white quiet zone", () => {
    const uri = "otpauth://totp/Example:me@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example";
    const { modules } = create(uri, { errorCorrectionLevel: "M" });
    let dark = 0;
    for (let r = 0; r < modules.size; r++) for (let c = 0; c < modules.size; c++) dark += modules.get(r, c) ? 1 : 0;

    render(<QrCode value={uri} label="QR" />);
    const svg = screen.getByRole("img", { name: "QR" });
    expect(svg.getAttribute("viewBox")).toBe(`0 0 ${modules.size + 8} ${modules.size + 8}`);
    expect(svg.querySelector("path")!.getAttribute("d")!.match(/M/g)).toHaveLength(dark);
  });
});
