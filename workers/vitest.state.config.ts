import { defineConfig } from "vitest/config";

// Pure protocol rules run without starting the Cloudflare runtime.
export default defineConfig({
  test: { environment: "node", include: ["test/state-version.test.ts"] },
});
