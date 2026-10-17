import { expect, it } from "vitest";
import { json, negotiated } from "../src/http";

it("compresses JSON exactly once", async () => {
  const response = await negotiated(
    new Request("https://beacon.test/healthz", { headers: { "Accept-Encoding": "gzip" } }),
    json({ status: "ok" }),
  );
  expect(response.headers.get("Content-Encoding")).toBe("gzip");
  const stream = response.body!.pipeThrough(new DecompressionStream("gzip"));
  expect(await new Response(stream).json()).toEqual({ status: "ok" });
});

it("does not compress identity requests or bodyless responses", async () => {
  const request = new Request("https://beacon.test/healthz", {
    headers: { "Accept-Encoding": "identity" },
  });
  const response = await negotiated(request, json({ status: "ok" }));
  expect(response.headers.get("Content-Encoding")).toBeNull();
  expect(await response.json()).toEqual({ status: "ok" });
  const empty = await negotiated(
    new Request("https://beacon.test", { headers: { "Accept-Encoding": "gzip" } }),
    new Response(null, { status: 204 }),
  );
  expect(empty.status).toBe(204);
  expect(empty.headers.get("Content-Encoding")).toBeNull();
});
