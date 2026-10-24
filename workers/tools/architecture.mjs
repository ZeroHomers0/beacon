import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, posix } from "node:path";

// Explicit dependency policy: API depends on ports, storage never owns HTTP.
const policy = {
  "domain.ts": [],
  "state-version.ts": [],
  "blocks.ts": ["domain.ts", "state-version.ts"],
  "protocol.ts": [],
  "states.ts": ["domain.ts", "blocks.ts", "state-version.ts"],
  "ports.ts": ["domain.ts", "states.ts"],
  "http.ts": ["domain.ts"],
  "bark.ts": ["domain.ts", "http.ts"],
  "api.ts": ["blocks.ts", "domain.ts", "protocol.ts", "ports.ts", "http.ts", "bark.ts"],
  "store/schema.ts": ["domain.ts", "states.ts"],
  "store/identity.ts": ["domain.ts", "protocol.ts"],
  "store/messages.ts": ["states.ts", "domain.ts", "ports.ts", "store/identity.ts"],
  "index.ts": [
    "domain.ts",
    "protocol.ts",
    "ports.ts",
    "api.ts",
    "http.ts",
    "store/schema.ts",
    "store/messages.ts",
  ],
};
const graph = new Map();
for (const [file, allowed] of Object.entries(policy)) {
  const source = readFileSync(`src/${file}`, "utf8");
  const edges = [];
  for (const match of source.matchAll(
    /(?:import|export)\s[^;]*?from\s*["']([^"']+)["']/g,
  )) {
    const target = match[1];
    if (!target.startsWith(".")) {
      assert.ok(
        file === "index.ts" && target === "cloudflare:workers",
        `${file}: unexpected runtime dependency ${target}`,
      );
      continue;
    }
    const resolved = `${posix.normalize(posix.join(dirname(file).replaceAll("\\", "/"), target))}.ts`;
    assert.ok(
      allowed.includes(resolved),
      `${file}: forbidden dependency ${resolved}`,
    );
    edges.push(resolved);
  }
  if (file.startsWith("store/"))
    assert.ok(
      !/\b(?:Request|Response|WebSocket)\b/.test(source),
      `${file}: storage must not own transport`,
    );
  assert.ok(
    !/\b(?:setInterval|setTimeout)\s*\(/.test(source),
    `${file}: use durable event scheduling instead of timers`,
  );
  // Protocol numbers live only in protocol.ts. A literal here would drift from
  // the Go server and the Android client the moment either side bumps.
  assert.ok(
    file === "protocol.ts" ||
      !/\b(?:protocol|admin_protocol|delivery_retention_protocol)\s*:\s*\d/.test(
        source,
      ),
    `${file}: import protocol constants from protocol.ts instead of a literal`,
  );
  graph.set(file, edges);
}
function walk(file, stack = []) {
  assert.ok(
    !stack.includes(file),
    `dependency cycle: ${[...stack, file].join(" -> ")}`,
  );
  for (const next of graph.get(file)) walk(next, [...stack, file]);
}
for (const file of graph.keys()) walk(file);
console.log(
  "Workers architecture passed: dependency boundaries, acyclic imports, durable scheduling.",
);
