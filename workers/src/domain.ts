export const VERSION = "1.2.4-workers";
export const DAY = 86_400_000;
export const PENDING = 7 * DAY;
export const RECEIPT = 30 * DAY;
export const GRACE = 30 * 60_000;
export const MAX_REQUEST = 512 * 1024;
export const BATCH = 100;
export const MAINTENANCE = 60 * 60_000;
export type Row = Record<string, SqlStorageValue>;

export interface PushOptions {
  key: string;
  credential: string;
  fingerprint: string;
  group: string;
  ttl: number;
  actions?: MessageAction[];
}
export function pushOptions(
  group: unknown,
  ttl: unknown,
  key: string,
  credential: string,
  fingerprint: string,
): PushOptions {
  if (group === undefined || group === null) group = "";
  if (
    typeof group !== "string" ||
    bytes(group) > 128 ||
    !/^[\x21-\x7e]{0,128}$/.test(key)
  )
    reject(400, "invalid_message");
  if (ttl === undefined || ttl === null) ttl = 0;
  if (
    typeof ttl !== "number" ||
    !Number.isInteger(ttl) ||
    ttl < 0 ||
    ttl > 604800
  )
    reject(400, "invalid_message");
  return { key, credential, fingerprint, group, ttl };
}

export interface MessageAction {
  title: string;
  url: string;
}
export function validateMessageActions(value: unknown): MessageAction[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > 1) reject(400, "invalid_message");
  return value.map((item) => {
    if (
      !item ||
      typeof item !== "object" ||
      Array.isArray(item) ||
      Object.keys(item).some((key) => !["title", "url"].includes(key))
    )
      reject(400, "invalid_message");
    const { title, url } = item;
    if (
      typeof title !== "string" ||
      !title.trim() ||
      bytes(title) > 64 ||
      /[\u0000-\u001f\u007f-\u009f]/.test(title) ||
      typeof url !== "string" ||
      bytes(url) > 2048 ||
      !/^https:\/\//i.test(url) ||
      /[\s\\\u0000-\u001f\u007f-\u009f]/.test(url)
    )
      reject(400, "invalid_message");
    const authority = url.slice(url.indexOf("//") + 2).split(/[/?#]/)[0];
    if (
      !authority ||
      authority.includes("@") ||
      authority.endsWith(":") ||
      /%(?![0-9a-f]{2})/i.test(url) ||
      /[^\x00-\x7f]/.test(authority)
    )
      reject(400, "invalid_message");
    try {
      const parsed = new URL(url);
      if (
        parsed.protocol !== "https:" ||
        !parsed.hostname ||
        parsed.username ||
        parsed.password ||
        (parsed.port !== "" && Number(parsed.port) < 1) ||
        parsed.hostname.length > 253 ||
        (!/^\[[a-f0-9:]+\]$/i.test(parsed.hostname) &&
          !parsed.hostname
            .replace(/\.$/, "")
            .split(".")
            .every((label) =>
              /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label),
            ))
      )
        reject(400, "invalid_message");
    } catch {
      reject(400, "invalid_message");
    }
    return { title: Array.from(title.trim()).slice(0, 4).join(""), url };
  });
}
export interface Message {
  application_name?: string;
  group?: string;
  expires_at_ms: number;
  id: string;
  sequence: number;
  title: string;
  subtitle: string;
  body: string;
  created_at_ms: number;
  priority: number;
  application_id?: string;
  bark?: string;
  actions?: MessageAction[];
}
export interface Sender {
  device: string;
  application: string;
  name?: string;
  priority: number;
}
export interface Attachment {
  device: string;
  hash: string;
  control?: boolean;
  batch?: boolean;
  dirty?: boolean;
}
export class Fault extends Error {
  constructor(
    public status: number,
    public code: string,
    public headers: Record<string, string> = {},
  ) {
    super(code);
  }
}
export function reject(status: number, code: string): never {
  throw new Fault(status, code);
}
export function bytes(value: string): number {
  return new TextEncoder().encode(value).length;
}
export function token(length = 32): string {
  return [...crypto.getRandomValues(new Uint8Array(length))]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
export async function hash(value: string): Promise<string> {
  return [
    ...new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
    ),
  ]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
export function validID(value: unknown): value is string {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}
export function name(value: unknown, code: string, allowEmpty = false): string {
  if (typeof value !== "string") reject(400, code);
  const result = value.trim();
  if ((!allowEmpty && !result) || bytes(result) > 128) reject(400, code);
  return result;
}
export function priority(value: unknown, fallback = 5): number {
  if (value === undefined || value === null) return fallback;
  if (
    typeof value !== "number" ||
    !Number.isInteger(value) ||
    value < 0 ||
    value > 10
  )
    reject(400, "invalid_message");
  return value;
}
export function message(row: Row): Message {
  return {
    ...(row.application_name
      ? { application_name: String(row.application_name) }
      : {}),
    ...(row.message_group ? { group: String(row.message_group) } : {}),
    expires_at_ms: Number(row.expires_at),
    id: String(row.id),
    sequence: Number(row.seq),
    title: String(row.title),
    subtitle: String(row.subtitle),
    body: String(row.body),
    created_at_ms: Number(row.created),
    priority: Number(row.priority),
    ...(row.application_id
      ? { application_id: String(row.application_id) }
      : {}),
    ...(row.actions
      ? { actions: JSON.parse(String(row.actions)) as MessageAction[] }
      : {}),
    ...(row.bark ? { bark: String(row.bark) } : {}),
  };
}
export function retry(attempts: number): number {
  return Math.min(300_000, 5000 * 2 ** Math.min(6, Math.max(0, attempts - 1)));
}
