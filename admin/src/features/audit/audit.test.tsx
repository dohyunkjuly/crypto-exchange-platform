import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchAudit: vi.fn(), fetchActions: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }), usePathname: () => "/audit",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
const { Audit } = await import("./Audit");

const entry = {
  id: 9, adminId: 1, adminEmail: "ops@x.dev", action: "USER_FROZEN", targetType: "user", targetId: "42",
  before: '{"status":"ACTIVE"}', after: '{"status":"SUSPENDED","reason":"fraud"}', ipAddress: "10.0.0.1",
  createdAt: "2026-09-23T14:05:00Z",
};

beforeEach(() => {
  nav.query = "";
  api.fetchActions.mockResolvedValue(["LOGIN", "USER_FROZEN"]);
  api.fetchAudit.mockResolvedValue({ items: [entry, { ...entry, id: 8, adminId: null, adminEmail: null, action: "LOGIN_FAILED", targetType: "admin", targetId: "x@x", before: null, after: "BAD_PASSWORD" }], total: 2, page: 0, size: 50 });
});
afterEach(() => vi.clearAllMocks());

describe("Audit log", () => {
  it("shows who did what to whom, links a customer target, and reads before → after as stored", async () => {
    render(<Audit />);
    // The action filter offers USER_FROZEN too; the row is the cell that says it.
    const row = (await screen.findByRole("cell", { name: "USER_FROZEN" })).closest("tr")!;
    expect(within(row).getByText("ops@x.dev")).toBeInTheDocument();
    expect(within(row).getByRole("link", { name: "user 42" })).toHaveAttribute("href", "/users/42");
    expect(within(row).getByTitle("before")).toHaveTextContent('{"status":"ACTIVE"}');
    expect(within(row).getByTitle("after")).toHaveTextContent("fraud");

    // A failed login has no admin behind it and says so rather than inventing one.
    const failed = screen.getByRole("cell", { name: "LOGIN_FAILED" }).closest("tr")!;
    expect(within(failed).getAllByText("—").length).toBeGreaterThan(0);
  });

  it("puts the filters in the URL, as UTC days", async () => {
    const u = userEvent.setup();
    render(<Audit />);
    await screen.findByRole("cell", { name: "USER_FROZEN" });

    await u.selectOptions(screen.getByRole("combobox", { name: "Action" }), "USER_FROZEN");
    await u.type(screen.getByRole("textbox", { name: "Admin id" }), "1");
    await u.click(screen.getByRole("button", { name: "Search" }));
    expect(nav.replace).toHaveBeenCalledWith("/audit?adminId=1&action=USER_FROZEN");
  });

  it("keeps its columns when nothing matches", async () => {
    nav.query = "action=LOGIN";
    api.fetchAudit.mockResolvedValue({ items: [], total: 0, page: 0, size: 50 });
    render(<Audit />);
    expect(await screen.findByText("Nothing recorded.")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Change" })).toBeInTheDocument();
  });
});
