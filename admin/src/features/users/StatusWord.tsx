import type { Summary } from "./api";

/**
 * A word in a pill: the word carries the state, the colour only repeats it. "Frozen with master"
 * is its own state -- the sub reads as frozen because its master is, and thawing the master will
 * release it, which is not something an admin should have to remember from the audit log.
 * Likewise "Trading suspended with master": the sub's own row says nothing, the gate reads the
 * master's.
 *
 * @param masterSuspended the master of this sub has trading suspended
 */
export function StatusWord({ user, masterSuspended = false }: { user: Summary; masterSuspended?: boolean }) {
  if (user.status === "SUSPENDED") {
    return (
      <span className="pill pill-bad" title={user.frozenReason ?? undefined}>
        {user.frozenWithMaster ? "Frozen with master" : "Frozen"}
      </span>
    );
  }
  if (user.status === "CLOSED") return <span className="pill pill-off">Closed</span>;
  if (user.tradingSuspendedAt) {
    return <span className="pill pill-warn" title={user.tradingSuspendedReason ?? undefined}>Trading suspended</span>;
  }
  if (masterSuspended) return <span className="pill pill-warn">Trading suspended with master</span>;
  return <span className="pill pill-ok">Active</span>;
}
