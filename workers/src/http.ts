import { Fault, MAX_REQUEST, reject } from "./domain";

export function json(
  value: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...headers,
    },
  });
}
export function error(error: unknown): Response {
  return error instanceof Fault
    ? json({ error: error.code }, error.status, error.headers)
    : json({ error: "storage_error" }, 500);
}
export function method(request: Request, expected: string): void {
  if (request.method !== expected)
    throw new Fault(405, "method_not_allowed", { Allow: expected });
}
export function bearer(request: Request): string {
  const value = request.headers.get("Authorization") ?? "";
  if (!/^Bearer [a-f0-9]{64}$/.test(value)) reject(401, "unauthorized");
  return value.slice(7);
}
export async function read(
  request: Request,
  limit = MAX_REQUEST,
): Promise<string> {
  const reader = request.body?.getReader();
  if (!reader) return "";
  const parts: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) reject(413, "request_too_large");
      parts.push(value);
    }
  } finally {
    await reader.cancel();
  }
  const raw = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    raw.set(part, offset);
    offset += part.length;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(
      raw,
    );
  } catch {
    return reject(400, "invalid_request");
  }
}

// JSON.parse silently accepts duplicate fields. Keep Go's strict contract and
// reject ambiguous objects and malformed Unicode before applying business rules.
export function parse(raw: string): unknown {
  let at = 0;
  function whitespace() {
    while (/[ \t\r\n]/.test(raw[at] ?? "") && at < raw.length) at++;
  }
  function string(): string {
    const start = at++;
    while (at < raw.length) {
      if (raw[at] === "\\") {
        at += 2;
        continue;
      }
      if (raw[at++] === '"') {
        const value: string = JSON.parse(raw.slice(start, at));
        for (let i = 0; i < value.length; i++) {
          const unit = value.charCodeAt(i);
          if (unit >= 0xd800 && unit <= 0xdbff) {
            const next = value.charCodeAt(++i);
            if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error("unicode");
          } else if (unit >= 0xdc00 && unit <= 0xdfff)
            throw new Error("unicode");
        }
        return value;
      }
    }
    throw new Error("string");
  }
  function value(depth: number): unknown {
    if (depth > 8) throw new Error("depth");
    whitespace();
    if (raw[at] === '"') return string();
    if (raw[at] === "{") {
      at++;
      whitespace();
      const object: Record<string, unknown> = Object.create(null);
      if (raw[at] === "}") {
        at++;
        return object;
      }
      for (;;) {
        whitespace();
        if (raw[at] !== '"') throw new Error("key");
        const key = string();
        whitespace();
        if (Object.hasOwn(object, key) || raw[at++] !== ":")
          throw new Error("key");
        object[key] = value(depth + 1);
        whitespace();
        const end = raw[at++];
        if (end === "}") return object;
        if (end !== ",") throw new Error("object");
      }
    }
    if (raw[at] === "[") {
      at++;
      whitespace();
      const result: unknown[] = [];
      if (raw[at] === "]") {
        at++;
        return result;
      }
      for (;;) {
        result.push(value(depth + 1));
        whitespace();
        const end = raw[at++];
        if (end === "]") return result;
        if (end !== ",") throw new Error("array");
      }
    }
    const match =
      /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(
        raw.slice(at),
      );
    if (!match) throw new Error("value");
    at += match[0].length;
    return JSON.parse(match[0]);
  }
  const result = value(0);
  whitespace();
  if (at !== raw.length) throw new Error("trailing");
  return result;
}
export async function body(
  request: Request,
  fields: string[],
  code = "invalid_request",
  limit = MAX_REQUEST,
): Promise<Record<string, unknown>> {
  const raw = await read(request, limit);
  try {
    const result = parse(raw);
    if (!result || Array.isArray(result) || typeof result !== "object")
      throw new Error("object");
    if (Object.keys(result).some((key) => !fields.includes(key)))
      throw new Error("field");
    return result as Record<string, unknown>;
  } catch {
    return reject(400, code);
  }
}
export function text(value: unknown, fallback = ""): string {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "string") reject(400, "invalid_message");
  return value;
}
export function integer(
  value: string | null,
  fallback: number,
  min: number,
  max: number,
  code: string,
): number {
  if (value === null || value === "") return fallback;
  if (!/^\d+$/.test(value)) reject(400, code);
  const result = Number(value);
  if (!Number.isSafeInteger(result) || result < min || result > max)
    reject(400, code);
  return result;
}
export function ids(value: unknown, code: string): string[] {
  if (
    !Array.isArray(value) ||
    value.length < 1 ||
    value.length > 100 ||
    value.some((id) => typeof id !== "string" || !/^[a-fA-F0-9]{64}$/.test(id))
  )
    reject(400, code);
  return value;
}
