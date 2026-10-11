import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const testEnv = { ...process.env };
if (process.platform === "win32") {
  // workerd cannot use some Windows 8.3 TEMP paths (e.g. ADMINI~1).
  // Change only the child test process, never the user's environment.
  const temp = resolve(".wrangler/runtime-temp");
  mkdirSync(temp, { recursive: true });
  testEnv.TEMP = temp;
  testEnv.TMP = temp;
}
const result = spawnSync(
  process.execPath,
  ["node_modules/vitest/vitest.mjs", "run", ...process.argv.slice(2)],
  { env: testEnv, stdio: "inherit" },
);
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
