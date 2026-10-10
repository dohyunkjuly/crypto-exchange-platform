import type { CopyResult, FollowStatus } from "./api";

/** One colour per state, the same on the list and on the account's own page. */
export const statusTone = (status: FollowStatus) =>
  status === "ACTIVE" ? "text-up" : status === "CLOSING" ? "text-text" : "text-muted";

export const resultTone = (result: CopyResult) =>
  result === "FILLED" ? "text-up" : result === "FAILED" ? "text-danger" : "text-muted";
