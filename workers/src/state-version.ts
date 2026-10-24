/** Pure state rules. Call inside the transaction that persists the snapshot. */
export const STRUCTURED_SCHEMA_VERSION = 1;
export interface StateVersion {
  revision: number;
  digest: string;
  deleted: boolean;
  task: boolean;
  status: string;
}
export function validTopicKey(key: string): boolean {
  return key.length > 0 && new TextEncoder().encode(key).length <= 128 &&
    !/[\p{Cc}\p{Cs}]/u.test(key);
}
export function validStateStatus(task: boolean, status: string): boolean {
  return task ? ["running", "paused", "completed", "failed", "cancelled"].includes(status) : status === "";
}
export type StateVersionResult = "changed" | "unchanged" | "invalid_message" |
  "stale_state_revision" | "state_revision_conflict" | "invalid_state_transition";
export function checkStateVersion(previous: StateVersion | null, next: StateVersion): StateVersionResult {
  if (!Number.isSafeInteger(next.revision) || next.revision < 1 ||
      (!next.deleted && !validStateStatus(next.task, next.status))) return "invalid_message";
  if (!previous) return "changed";
  if (next.revision < previous.revision) return "stale_state_revision";
  if (next.revision === previous.revision) {
    return next.digest === previous.digest && next.deleted === previous.deleted &&
      next.task === previous.task && next.status === previous.status ? "unchanged" : "state_revision_conflict";
  }
  if (previous.task !== next.task) return "invalid_state_transition";
  const terminal = ["completed", "failed", "cancelled"].includes(previous.status);
  if (previous.task && !next.deleted && (previous.deleted || terminal && previous.status !== next.status))
    return "invalid_state_transition";
  return "changed";
}
