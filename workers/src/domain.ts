export const VERSION = "1.1.1-workers";
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
    ...(row.bark ? { bark: String(row.bark) } : {}),
  };
}
export function retry(attempts: number): number {
  return Math.min(300_000, 5000 * 2 ** Math.min(6, Math.max(0, attempts - 1)));
}
