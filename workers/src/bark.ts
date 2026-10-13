import { Fault, bytes, reject } from "./domain";
import { parse, read } from "./http";

// Every Bark parameter Beacon honours. Unsupported parameters (badge, action, ...) are silently dropped so third-party software only has to
// change the key to reach Beacon.
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
  "group",
  "ttl",
];
// Unsupported parameters are dropped, including a repeated unsupported one. A
// repeated supported parameter stays a malformed request: the server cannot tell
// which value the client meant, so the request is rejected.
function decode(raw: string): string {
  return decodeURIComponent(raw.replace(/\+/g, " "));
}
function query(raw: string): Record<string, string> {
  const result: Record<string, string> = Object.create(null);
  if (!raw) return result;
  for (const pair of raw.split("&")) {
    if (!pair) continue;
    // Go's url.ParseQuery fails the whole query on a semicolon separator or a
    // malformed percent escape, whatever the parameter name is.
    if (/;|%(?![0-9A-Fa-f]{2})/.test(pair)) reject(400, "invalid_query");
    const at = pair.indexOf("=");
    let key: string;
    try {
      key = decode(at < 0 ? pair : pair.slice(0, at));
    } catch {
      // Go decodes this to a byte string that names no field, so it is dropped.
      continue;
    }
    const known = FIELDS.includes(key);
    let value: string;
    try {
      value = decode(at < 0 ? "" : pair.slice(at + 1));
    } catch {
      // Go keeps the invalid bytes; that is only fatal for a name we honour.
      if (known) reject(400, "invalid_query");
      continue;
    }
    if (!known) continue;
    if (Object.hasOwn(result, key)) reject(400, "invalid_query");
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
    // Qinglong sets both Content-Type and content-type; Fetch combines them.
    // Accept repeated declarations only when they identify the same format.
    const declarations = (request.headers.get("Content-Type") ?? "")
      .split(",")
      .map((value) => value.trim());
    const media = declarations[0];
    if (
      !declarations.every(
        (value) =>
          /^(application\/json|application\/x-www-form-urlencoded)(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?\s*$/i.test(value) &&
          value.split(";")[0].trim().toLowerCase() ===
            media.split(";")[0].trim().toLowerCase(),
      )
    )
      reject(415, "unsupported_media_type");
    const raw = await read(request);
    try {
      if (media.toLowerCase().startsWith("application/json")) {
        // Unsupported parameters are dropped whatever their JSON type; a
        // non-string value for a parameter Beacon honours is a client bug.
        // parse() already rejects duplicate keys for every key.
        const value = parse(raw);
        if (!value || typeof value !== "object" || Array.isArray(value))
          throw new Error("fields");
        const filtered: Record<string, string> = {};
        for (const [key, item] of Object.entries(value)) {
          if (!FIELDS.includes(key)) continue;
          if (
            key === "ttl" &&
            typeof item === "number" &&
            Number.isInteger(item)
          ) {
            filtered[key] = String(item);
            continue;
          }
          if (typeof item !== "string") throw new Error("field");
          filtered[key] = item;
        }
        values = filtered;
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
  // Only forward bounded presentation data. The server never fetches media or
  // receives AES keys. A field that fails its own validation is dropped rather
  // than failing the whole push: a third-party client must not lose its message
  // because it sent one unsupported or malformed presentation field. ciphertext
  // and iv are coupled on purpose, so either half failing drops both and no
  // undecryptable payload is ever stored.
  const extra: Record<string, string> = {};
  for (const key of ["image", "icon", "url", "sound"]) {
    const value = values[key];
    if (!value) continue;
    if (["image", "icon", "url"].includes(key)) {
      let url: URL;
      try {
        url = new URL(value);
      } catch {
        continue;
      }
      // Go drops any URL carrying a userinfo component, including the empty
      // "https://@host" form, so inspect the authority text directly.
      const authority = value
        .slice(value.indexOf("://") + 3)
        .split(/[/?#]/, 1)[0];
      if (
        bytes(value) > 2048 ||
        url.protocol !== "https:" ||
        !url.hostname ||
        authority.includes("@")
      )
        continue;
    }
    if (key === "sound" && bytes(value) > 128) continue;
    extra[key] = value;
  }
  // ciphertext and iv are coupled on purpose: either half failing drops both,
  // so the server never stores a payload it could not decrypt.
  let ciphertext = validCiphertext(values.ciphertext ?? "");
  const iv = values.iv ?? "";
  // iv is compared in UTF-8 bytes, exactly like Go's len([]byte(value)).
  let validIV = iv !== "" && bytes(iv) === 16;
  if (
    ((values.ciphertext ?? "") !== "" && ciphertext === "") ||
    (iv !== "" && !validIV)
  ) {
    ciphertext = "";
    validIV = false;
  }
  if (ciphertext !== "") extra.ciphertext = ciphertext;
  if (validIV) extra.iv = iv;
  // Bark's own semantics give ciphertext precedence: when it survives
  // validation, any plaintext alongside it is ignored rather than rejected.
  const encrypted = extra.ciphertext !== undefined;
  if (encrypted) {
    values.title = "";
    values.subtitle = "";
  }
  const level = values.level ?? "";
  const priority =
    values.sound === "none" || level === "passive"
      ? 2
      : ["timeSensitive", "critical"].includes(level)
        ? 8
        : 5;
  return {
    bark: Object.keys(extra).length ? JSON.stringify(extra) : "",
    priority,
    body: encrypted ? "加密消息" : (values.body ?? ""),
  };
}

// validCiphertext returns the ciphertext when it is a bounded standard base64
// AES block sequence, and "" when it must be dropped.
function validCiphertext(value: string): string {
  if (!value || value.length > 90000) return "";
  let raw: string;
  try {
    raw = atob(value);
  } catch {
    return "";
  }
  if (
    raw.length < 16 ||
    raw.length > 65552 ||
    raw.length % 16 ||
    btoa(raw) !== value
  )
    return "";
  return value;
}
