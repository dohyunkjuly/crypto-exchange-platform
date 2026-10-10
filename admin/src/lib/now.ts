import { useState } from "react";

/** Now, read once when the component mounts, so render stays pure and "5h ago" does not tick
 *  mid-read. A page shows the time it was opened; reopening it is how it moves. */
export function useNow(): number {
  const [now] = useState(() => Date.now());
  return now;
}
