import type { Message, Sender } from "./domain";

export interface Credentials {
  server_id: string;
  device_id: string;
  receive_token: string;
  push_key: string;
  protocol: number;
}
export interface Repository {
  readonly serverID: string;
  receiver(hash: string, active?: boolean): string;
  sender(hash: string, applications?: boolean): Sender;
  active(device: string): void;
  expiredCount(device: string): number;
  admit(key: string, now: number, burst: number, rate: number): void;
  createBinding(
    value: unknown,
    hash: string,
    code: string,
    now: number,
  ): { binding_code: string; device_name: string; expires_at_ms: number };
  bind(
    codeHash: string,
    value: unknown,
    receiveHash: string,
    pushHash: string,
    receive: string,
    push: string,
    now: number,
  ): Credentials;
  revoke(device: string): void;
  devices(): Record<string, unknown>[];
  applications(): Record<string, unknown>[];
  createApplication(
    device: string,
    value: unknown,
    priority: number,
    hash: string,
    credential: string,
  ): Record<string, unknown>;
  revokeApplication(id: string): void;
  save(
    sender: Sender,
    title: string,
    subtitle: string,
    body: string,
    priority: number,
    bark: string,
    now: number,
    options?: import("./domain").PushOptions,
  ): Message;
  page(
    device: string,
    after: number,
    limit: number,
    pending: boolean,
    now: number,
  ): {
    server_id: string;
    messages: Message[];
    next_cursor: number;
    has_more: boolean;
  };
  statuses(device: string, ids: string[], now: number): Record<string, string>;
  ack(device: string, ids: string[], now: number): void;
  clearMessages(device?: string, deleteDevice?: boolean): number;
  stats(): Record<string, unknown>;
}
export interface Transport {
  // Arm durable recovery BEFORE a mutation; immediate delivery is an optimization.
  arm(): Promise<void>;
  deliver(device?: string): void;
  disconnect(): void;
  stream(request: Request, device: string, hash: string): Response;
}
