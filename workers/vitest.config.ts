import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: {
          ADMIN_TOKEN: "test-only-admin-token-32-characters",
          HISTORY_RETENTION_DAYS: "30",
        },
      },
    }),
  ],
  test: { testTimeout: 15000 },
});
