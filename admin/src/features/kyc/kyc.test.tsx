import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const nav = vi.hoisted(() => ({ replace: vi.fn(), query: "" }));
const api = vi.hoisted(() => ({ fetchQueue: vi.fn(), approveKyc: vi.fn(), rejectKyc: vi.fn() }));
const session = vi.hoisted(() => ({ role: "OPERATOR" }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: nav.replace }), usePathname: () => "/kyc",
  useSearchParams: () => new URLSearchParams(nav.query),
}));
vi.mock("./api", async (orig) => ({ ...(await orig<object>()), ...api }));
vi.mock("@/lib/session", () => ({ useAdmin: () => ({ id: 1, email: "ops@x.dev", role: session.role }) }));
const { Kyc } = await import("./Kyc");

const submission = {
  user: { id: 7, email: "ann@x.dev", name: null, type: "PRIMARY", status: "ACTIVE", frozenReason: null, frozenWithMaster: false,
    kycLevel: "NONE", kycVerifiedUntil: null, mfaEnabled: true, emailVerified: true, createdAt: "2026-09-01T00:00:00Z" },
  status: "PENDING", level: "NONE", legalFirstName: "Ann", legalLastName: "Example", dateOfBirth: "1990-01-02",
  nationality: "KR", residenceCountry: "KR", submittedAt: new Date(Date.now() - 5 * 3_600_000).toISOString(),
  reviewedAt: null, expiresAt: null, rejectionReason: null,
};

beforeEach(() => {
  nav.query = "";
  session.role = "OPERATOR";
  api.fetchQueue.mockResolvedValue({ items: [submission], total: 1, page: 0, size: 25 });
});
afterEach(() => vi.clearAllMocks());

describe("KYC review", () => {
  it("shows what was typed and how long it has waited, and approves only with a code", async () => {
    const u = userEvent.setup();
    api.approveKyc.mockResolvedValue({ ...submission, status: "APPROVED", level: "BASIC" });
    render(<Kyc />);

    const row = (await screen.findByText("ann@x.dev")).closest("tr")!;
    expect(within(row).getByText("Ann Example")).toBeInTheDocument();
    expect(within(row).getByText("5h")).toBeInTheDocument();

    await u.click(within(row).getByRole("button", { name: "Approve" }));
    const dialog = screen.getByRole("dialog");
    const approve = within(dialog).getByRole("button", { name: "Approve" });
    expect(approve).toBeDisabled();                       // no code yet
    await u.type(within(dialog).getByLabelText("Authenticator code"), "123456");
    await u.click(approve);

    expect(api.approveKyc).toHaveBeenCalledWith(7, "BASIC", expect.any(String), "123456");
    expect(await screen.findByRole("status")).toHaveTextContent("approved to BASIC");
    expect(api.fetchQueue).toHaveBeenCalledTimes(2);      // the queue is re-read, not assumed
  });

  it("a rejection needs a reason the customer will read, and the code", async () => {
    const u = userEvent.setup();
    api.rejectKyc.mockResolvedValue({ ...submission, status: "REJECTED" });
    render(<Kyc />);

    await u.click(within((await screen.findByText("ann@x.dev")).closest("tr")!).getByRole("button", { name: "Reject" }));
    const dialog = screen.getByRole("dialog");
    await u.type(within(dialog).getByLabelText("Authenticator code"), "654321");
    expect(within(dialog).getByRole("button", { name: "Reject" })).toBeDisabled();   // code alone is not enough
    await u.type(within(dialog).getByLabelText(/Reason/), "Name does not match");
    await u.click(within(dialog).getByRole("button", { name: "Reject" }));

    expect(api.rejectKyc).toHaveBeenCalledWith(7, "Name does not match", "654321");
  });

  it("a VIEWER sees the queue and no way to decide it", async () => {
    session.role = "VIEWER";
    render(<Kyc />);
    await screen.findByText("ann@x.dev");
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });

  it("keeps its columns when nothing is waiting, and the tab in the URL", async () => {
    const u = userEvent.setup();
    api.fetchQueue.mockResolvedValue({ items: [], total: 0, page: 0, size: 25 });
    render(<Kyc />);
    expect(await screen.findByText("Nothing is waiting.")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Legal name" })).toBeInTheDocument();

    await u.click(screen.getByRole("tab", { name: "Rejected" }));
    expect(nav.replace).toHaveBeenCalledWith("/kyc?status=REJECTED");
  });
});
