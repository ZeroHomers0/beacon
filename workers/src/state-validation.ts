import { bytes, reject } from "./domain";
import { validateBlocks } from "./blocks";
import { validStateStatus, validTopicKey } from "./state-version";

export function validateState(p: Record<string, unknown>, now: number): void {
  if (
    p.mode !== "state" ||
    p.schema_version !== 1 ||
    typeof p.topic_key !== "string" ||
    !validTopicKey(p.topic_key) ||
    !Number.isSafeInteger(p.revision) ||
    Number(p.revision) < 1
  )
    reject(400, "invalid_message");
  if (p.operation === "delete") {
    if (
      Object.keys(p).some(
        (k) =>
          ![
            "mode",
            "schema_version",
            "operation",
            "topic_key",
            "revision",
          ].includes(k),
      )
    )
      reject(400, "invalid_message");
    return;
  }
  if (p.operation !== undefined && p.operation !== "upsert")
    reject(400, "invalid_message");
  if (
    p.state_type !== undefined &&
    p.state_type !== "persistent" &&
    p.state_type !== "task"
  )
    reject(400, "invalid_message");
  if (!validStateStatus(p.state_type === "task", String(p.status ?? "")))
    reject(400, "invalid_message");
  const observed = p.observed_at_ms ?? 0,
    fresh = p.fresh_for_seconds ?? 0,
    ttl = p.ttl || 604800;
  if (
    !Number.isSafeInteger(observed) ||
    Number(observed) < 0 ||
    Number(observed) > now + 300000 ||
    !Number.isInteger(fresh) ||
    Number(fresh) < 0 ||
    Number(fresh) > Number(ttl)
  )
    reject(400, "invalid_message");
  if (typeof p.body !== "string" || !p.body || bytes(p.body) > 65536)
    reject(400, "invalid_message");
  for (const k of ["title", "subtitle"])
    if (
      p[k] !== undefined &&
      (typeof p[k] !== "string" || bytes(p[k] as string) > 1024)
    )
      reject(400, "invalid_message");
  validateBlocks(p.schema_version, p.blocks);
}
