import { describe, it, expect } from "vitest";
import { checkStateVersion, validTopicKey, type StateVersion } from "../src/state-version";

const version = (patch: Partial<StateVersion> = {}): StateVersion => ({
  revision: 2, digest: "snapshot", deleted: false, task: true, status: "running", ...patch,
});
describe("state version lifecycle", () => {
  it("accepts first snapshots and ignores identical retries", () => {
    expect(checkStateVersion(null, version())).toBe("changed");
    expect(checkStateVersion(version(), version())).toBe("unchanged");
  });
  it("rejects stale, conflicting and unsafe revisions", () => {
    expect(checkStateVersion(version(), version({revision: 1}))).toBe("stale_state_revision");
    expect(checkStateVersion(version(), version({digest: "other"}))).toBe("state_revision_conflict");
    expect(checkStateVersion(null, version({revision: 0}))).toBe("invalid_message");
    expect(checkStateVersion(null, version({revision: Number.MAX_SAFE_INTEGER + 1}))).toBe("invalid_message");
  });
  it("protects terminal tasks while allowing persistent state recreation", () => {
    expect(checkStateVersion(version(), version({revision: 3, status: "completed"}))).toBe("changed");
    expect(checkStateVersion(version({status: "completed"}), version({revision: 3}))).toBe("invalid_state_transition");
    expect(checkStateVersion(version({deleted: true}), version({revision: 3}))).toBe("invalid_state_transition");
    expect(checkStateVersion(version({task: false, status: "", deleted: true}), version({revision: 3, task: false, status: ""}))).toBe("changed");
  });
  it("bounds UTF-8 topic keys and rejects controls and malformed Unicode", () => {
    for (const key of ["", "a".repeat(129), "a\nb", "\ud800"]) expect(validTopicKey(key)).toBe(false);
    expect(validTopicKey("额度:账户一")).toBe(true);
  });
});
