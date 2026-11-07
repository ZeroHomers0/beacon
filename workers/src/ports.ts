import type {
  TopicRecord,
  TopicResult,
  TopicSummary,
  AdminPage,
  SyncPage,
  StateRecord,
} from "./topic-models";
import type { Message, Sender } from "./domain";

export interface Credentials {
  server_id: string;
  device_id: string;
  receive_token: string;
  push_key: string;
  protocol: number;
}
export interface TopicRepository {
  topicPage(
    device: string,
    q: URLSearchParams,
    now: number,
  ): SyncPage<TopicRecord>;
  adminTopics(p: Record<string, unknown>, now: number): AdminPage<TopicSummary>;
  adminTopic(p: Record<string, unknown>): TopicResult;
  mutateTopic(a: string, p: Record<string, unknown>, now: number): TopicResult;
}
export interface StateRepository {
  saveState(
    credential: string,
    p: Record<string, unknown>,
    digest: string,
    now: number,
  ): StateRecord;
  statePage(
    device: string,
    q: URLSearchParams,
    now: number,
  ): Record<string, unknown>;
  stateMeta(credential: string, topic: string): Record<string, unknown>;
  adminStates(p: Record<string, unknown>, now: number): Record<string, unknown>;
  adminState(p: Record<string, unknown>): Record<string, unknown>;
  clearAdminState(
    p: Record<string, unknown>,
    now: number,
  ): Record<string, unknown>;
}
export interface IdentityRepository {
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
  deleteApplication(id: string): number;
}
export interface MessageRepository {
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
}
export interface OperationsRepository {
  stats(): Record<string, unknown>;
  operationalStats?(now: number): Record<string, number>;
  ready?(): boolean;
}
export interface Repository
  extends IdentityRepository,
    MessageRepository,
    TopicRepository,
    StateRepository,
    OperationsRepository {}

export interface Transport {
  activeConnections?(): number;
  topicsChanged?(device: string): void;
  stateChanged?(device: string): void;
  // Arm durable recovery BEFORE a mutation; immediate delivery is an optimization.
  arm(): Promise<void>;
  deliver(device?: string): void;
  disconnect(): void;
  stream(request: Request, device: string, hash: string): Response;
}
