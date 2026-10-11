import { Fault, bytes, reject } from "./domain";
import { parse, read } from "./http";

const FIELDS = [
  "title",
  "subtitle",
  "body",
  "device_key",
  "level",
  "sound",
  "image",
  "icon",
  "url",
  "ciphertext",
  "iv",
];
function query(raw: string): Record<string, string> {
  const result: Record<string, string> = Object.create(null);
  if (!raw) return result;
  for (const pair of raw.split("&")) {
    if (!pair) continue;
    if (pair.includes(";")) reject(400, "invalid_query");
    const at = pair.indexOf("=");
    const key = decodeURIComponent(
      (at < 0 ? pair : pair.slice(0, at)).replace(/\+/g, " "),
    );
    const value = decodeURIComponent(
      (at < 0 ? "" : pair.slice(at + 1)).replace(/\+/g, " "),
    );
    if (!FIELDS.includes(key) || Object.hasOwn(result, key))
      reject(400, "invalid_query");
    result[key] = value;
  }
  return result;
}
export async function bark(request: Request): Promise<Record<string, string>> {
  const url = new URL(request.url);
  const isPush = url.pathname === "/push";
  if (
    url.pathname === "/" ||
    url.pathname.startsWith("/api/") ||
    url.pathname === "/admin"
  )
    reject(404, "not_found");
  if (!["GET", "POST"].includes(request.method))
    throw new Fault(405, "method_not_allowed", { Allow: "GET, POST" });
  if (isPush && request.method !== "POST")
    throw new Fault(405, "method_not_allowed", { Allow: "POST" });
  let parts: string[] = [];
  if (!isPush) {
    const raw = url.pathname.slice(1).split("/");
    if (raw.length < 1 || raw.length > 4) reject(404, "not_found");
    try {
      parts = raw.map(decodeURIComponent);
    } catch {
      reject(400, "invalid_path");
    }
    if (parts[0].length !== 64) reject(404, "not_found");
  }
  let values: Record<string, string> = {};
  if (request.method === "POST") {
    const media = request.headers.get("Content-Type") ?? "";
    if (
      !/^(application\/json|application\/x-www-form-urlencoded)(?:\s*;\s*charset\s*=\s*"?utf-8"?)?\s*$/i.test(
        media,
      )
    )
      reject(415, "unsupported_media_type");
    const raw = await read(request);
    try {
      if (media.toLowerCase().startsWith("application/json")) {
        const value = parse(raw);
        if (
          !value ||
          typeof value !== "object" ||
          Array.isArray(value) ||
          Object.entries(value).some(
            ([k, v]) => !FIELDS.includes(k) || typeof v !== "string",
          )
        )
          throw new Error("fields");
        values = value as Record<string, string>;
      } else values = query(raw);
    } catch {
      reject(400, "invalid_message");
    }
  }
  try {
    Object.assign(values, query(url.search.slice(1)));
  } catch {
    reject(400, "invalid_query");
  }
  if (!isPush) {
    values.device_key = parts[0];
    if (parts.length === 2) values.body = parts[1];
    if (parts.length === 3) {
      values.title = parts[1];
      values.body = parts[2];
    }
    if (parts.length === 4) {
      values.title = parts[1];
      values.subtitle = parts[2];
      values.body = parts[3];
    }
  }
  return values;
}
export function presentation(values: Record<string, string>): {
  bark: string;
  priority: number;
  body: string;
} {
  const extra: Record<string, string> = {};
  for (const key of ["image", "icon", "url", "sound", "ciphertext", "iv"]) {
    const value = values[key];
    if (!value) continue;
    if (["image", "icon", "url"].includes(key)) {
      let url: URL;
      try {
        url = new URL(value);
      } catch {
        return reject(400, "invalid_presentation");
      }
      if (
        bytes(value) > 2048 ||
        url.protocol !== "https:" ||
        !url.hostname ||
        url.username ||
        url.password
      )
        reject(400, "invalid_presentation");
    }
    if (key === "sound" && bytes(value) > 128)
      reject(400, "invalid_presentation");
    if (key === "iv" && bytes(value) !== 16)
      reject(400, "invalid_presentation");
    if (key === "ciphertext") {
      let raw: string;
      try {
        raw = atob(value);
      } catch {
        return reject(400, "invalid_presentation");
      }
      if (
        value.length > 90000 ||
        raw.length < 16 ||
        raw.length > 65552 ||
        raw.length % 16 ||
        btoa(raw) !== value
      )
        reject(400, "invalid_presentation");
    }
    extra[key] = value;
  }
  const level = values.level ?? "";
  if (!["", "active", "passive", "timeSensitive", "critical"].includes(level))
    reject(400, "invalid_presentation");
  const priority =
    values.sound === "none" || level === "passive"
      ? 2
      : ["timeSensitive", "critical"].includes(level)
        ? 8
        : 5;
  if (values.ciphertext && (values.title || values.subtitle || values.body))
    reject(400, "invalid_message");
  return {
    bark: Object.keys(extra).length ? JSON.stringify(extra) : "",
    priority,
    body: values.ciphertext ? "加密消息" : (values.body ?? ""),
  };
}
