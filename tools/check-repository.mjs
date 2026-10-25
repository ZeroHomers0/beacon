import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const rootFiles = new Set([".editorconfig", ".gitattributes", ".gitignore", "LICENSE", "README.md", "API.md", "AGENTS.md", "CLAUDE.md", "FRONTEND_GUIDELINES.md", "BACKEND_GUIDELINES.md", "REPOSITORY_GUIDELINES.md"]);
const directories = new Set([".github", ".githooks", "android", "server", "workers", "contract", "examples", "mcp", "tools", "docs"]);
const forbiddenDirectories = new Set(["artifacts", "docs-repo", ".local", "tmp", "temp", "scratch", "logs", "screenshots", "node_modules", "build", "dist", "coverage", "test-results", "playwright-report", ".gradle", ".kotlin", ".wrangler", ".idea", ".vscode", ".codex", ".agents", ".cache", "__pycache__", ".pytest_cache", ".ruff_cache", ".venv", "venv", "beacon-data"]);
export function violation(path) {
  const parts = path.replaceAll("\\", "/").split("/");
  const name = parts.at(-1);
  if (parts.length === 1 && !rootFiles.has(name)) return "unapproved root file";
  if (parts.length > 1 && !directories.has(parts[0])) return "unapproved root directory";
  if (parts.slice(0, -1).some(p => forbiddenDirectories.has(p.toLowerCase()) || /^(?:\.tmp[-_]|tmp[-_]|temp[-_]|scratch[-_])/i.test(p))) return "temporary, generated, private, or runtime directory";
  if (parts[0] === "docs" && parts[1] === "design") return "local-only design directory";
  if (/^(?:\.env|\.dev\.vars)(?:$|\.)/.test(name) && !/\.(?:example|sample)$/.test(name)) return "local environment or secrets";
  if (/^(?:local\.properties|device\.json|beacon-revisions\.json|Thumbs\.db|\.DS_Store|RELEASE_NOTES\.md|\.60s_to_beacon_state\.json)$/.test(name)) return "local configuration, runtime marker, or local release notes";
  if (/\.(?:apk|aab|exe|jks|keystore|pem|key|p12|pfx|log|pyc|tmp|bak|orig|rej|zip|7z|tar|gz|sqlite|sqlite3|db)(?:-(?:wal|shm))?$/i.test(name)) return "build output, archive, private key, database, or temporary file";
  return null;
}
if (process.argv.includes("--self-test")) {
  for (const p of ["artifacts/a.png", "docs/design/prototype.html", "tmp/a.js", "android/app/build/x.txt", "workers/node_modules/x/package.json", "server/.env", "server/beacon.db-wal", "server/a.key", "random/file.txt", "notes.txt"]) assert.ok(violation(p), p);
  for (const p of ["API.md", "android/gradle/wrapper/gradle-wrapper.jar", "android/app/src/main/res/drawable-nodpi/state_widget_preview.png", "contract/example.json", "server/internal/store/topics_test.go", "workers/test/states.test.ts", ".githooks/pre-commit"]) assert.equal(violation(p), null, p);
  console.log("Repository policy self-test passed.");
} else {
  const args = ["ls-files", "--cached", "-z"];
  if (!process.argv.includes("--staged")) args.push("--others", "--exclude-standard");
  const files = [...new Set(execFileSync("git", args, { cwd: root, encoding: "utf8", maxBuffer: 16 * 1024 * 1024 }).split("\0").filter(Boolean))];
  const errors = files.flatMap(p => { const reason = violation(p); return reason ? [`${p}: ${reason}`] : []; });
  if (errors.length) { console.error("Repository policy failed:\n" + errors.join("\n")); process.exitCode = 1; }
  else console.log(`Repository policy passed: ${files.length} files; no prohibited directories or artifacts.`);
}
