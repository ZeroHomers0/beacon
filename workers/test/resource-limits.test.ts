import { expect, it } from "vitest";
import { bytes, MAX_BODY, MAX_TITLE, validateMessageActions } from "../src/domain";

it("uses UTF-8 byte limits and rejects unpaired surrogates", () => {
  expect(bytes("中")).toBe(3);
  expect(bytes("😀")).toBe(4);
  expect(bytes("a".repeat(MAX_TITLE))).toBe(1024);
  expect(bytes("a".repeat(MAX_BODY))).toBe(65536);
  for (const value of ["\ud800", "\udc00", "\ud800a"]) {
    expect(() => bytes(value)).toThrow();
  }
});

it("allows only one custom action", () => {
  const action = { title: "查看消息", url: "https://example.com" };
  expect(validateMessageActions([action])).toHaveLength(1);
  expect(() => validateMessageActions([action, action])).toThrow();
});
