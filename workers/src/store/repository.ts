import { TopicStore } from "../topics";
import { StateStore } from "../states";
import { IdentityStore } from "./identity";
import { MessageStore } from "./messages";
import type { Repository } from "../ports";

/** SQL repository facade; feature stores share the same durable transaction owner. */
export class SqliteRepository implements Repository {
  private identity: IdentityStore;
  private messages: MessageStore;
  constructor(private storage: DurableObjectStorage) {
    this.identity = new IdentityStore(storage);
    this.messages = new MessageStore(storage, this.identity);
  }
  get serverID(): string {
    return this.identity.serverID;
  }
  private get topics() {
    return new TopicStore(this.storage, this.serverID);
  }
  private get states() {
    return new StateStore(this.storage, this.serverID);
  }
  rows = (
    ...args: Parameters<IdentityStore["rows"]>
  ): ReturnType<IdentityStore["rows"]> => this.identity.rows(...args);
  first = (
    ...args: Parameters<IdentityStore["first"]>
  ): ReturnType<IdentityStore["first"]> => this.identity.first(...args);
  receiver = (
    ...args: Parameters<IdentityStore["receiver"]>
  ): ReturnType<IdentityStore["receiver"]> => this.identity.receiver(...args);
  sender = (
    ...args: Parameters<IdentityStore["sender"]>
  ): ReturnType<IdentityStore["sender"]> => this.identity.sender(...args);
  active = (
    ...args: Parameters<IdentityStore["active"]>
  ): ReturnType<IdentityStore["active"]> => this.identity.active(...args);
  authorized = (
    ...args: Parameters<IdentityStore["authorized"]>
  ): ReturnType<IdentityStore["authorized"]> =>
    this.identity.authorized(...args);
  createBinding = (
    ...args: Parameters<IdentityStore["createBinding"]>
  ): ReturnType<IdentityStore["createBinding"]> =>
    this.identity.createBinding(...args);
  bind = (
    ...args: Parameters<IdentityStore["bind"]>
  ): ReturnType<IdentityStore["bind"]> => this.identity.bind(...args);
  revoke = (
    ...args: Parameters<IdentityStore["revoke"]>
  ): ReturnType<IdentityStore["revoke"]> => this.identity.revoke(...args);
  devices = (
    ...args: Parameters<IdentityStore["devices"]>
  ): ReturnType<IdentityStore["devices"]> => this.identity.devices(...args);
  applications = (
    ...args: Parameters<IdentityStore["applications"]>
  ): ReturnType<IdentityStore["applications"]> =>
    this.identity.applications(...args);
  createApplication = (
    ...args: Parameters<IdentityStore["createApplication"]>
  ): ReturnType<IdentityStore["createApplication"]> =>
    this.identity.createApplication(...args);
  revokeApplication = (
    ...args: Parameters<IdentityStore["revokeApplication"]>
  ): ReturnType<IdentityStore["revokeApplication"]> =>
    this.identity.revokeApplication(...args);
  deleteApplication = (
    ...args: Parameters<IdentityStore["deleteApplication"]>
  ): ReturnType<IdentityStore["deleteApplication"]> =>
    this.identity.deleteApplication(...args);
  admit = (
    ...args: Parameters<IdentityStore["admit"]>
  ): ReturnType<IdentityStore["admit"]> => this.identity.admit(...args);
  expiredCount = (
    ...args: Parameters<MessageStore["expiredCount"]>
  ): ReturnType<MessageStore["expiredCount"]> =>
    this.messages.expiredCount(...args);
  save = (
    ...args: Parameters<MessageStore["save"]>
  ): ReturnType<MessageStore["save"]> => this.messages.save(...args);
  page = (
    ...args: Parameters<MessageStore["page"]>
  ): ReturnType<MessageStore["page"]> => this.messages.page(...args);
  available = (
    ...args: Parameters<MessageStore["available"]>
  ): ReturnType<MessageStore["available"]> => this.messages.available(...args);
  statuses = (
    ...args: Parameters<MessageStore["statuses"]>
  ): ReturnType<MessageStore["statuses"]> => this.messages.statuses(...args);
  ack = (
    ...args: Parameters<MessageStore["ack"]>
  ): ReturnType<MessageStore["ack"]> => this.messages.ack(...args);
  due = (
    ...args: Parameters<MessageStore["due"]>
  ): ReturnType<MessageStore["due"]> => this.messages.due(...args);
  complete = (
    ...args: Parameters<MessageStore["complete"]>
  ): ReturnType<MessageStore["complete"]> => this.messages.complete(...args);
  clearMessages = (
    ...args: Parameters<MessageStore["clearMessages"]>
  ): ReturnType<MessageStore["clearMessages"]> =>
    this.messages.clearMessages(...args);
  ready = (
    ...args: Parameters<MessageStore["ready"]>
  ): ReturnType<MessageStore["ready"]> => this.messages.ready(...args);
  operationalStats = (
    ...args: Parameters<MessageStore["operationalStats"]>
  ): ReturnType<MessageStore["operationalStats"]> =>
    this.messages.operationalStats(...args);
  stats = (
    ...args: Parameters<MessageStore["stats"]>
  ): ReturnType<MessageStore["stats"]> => this.messages.stats(...args);
  maintain = (
    ...args: Parameters<MessageStore["maintain"]>
  ): ReturnType<MessageStore["maintain"]> => this.messages.maintain(...args);
  nextWake = (
    ...args: Parameters<MessageStore["nextWake"]>
  ): ReturnType<MessageStore["nextWake"]> => this.messages.nextWake(...args);
  topicPage(device: string, q: URLSearchParams, now: number) {
    this.active(device);
    return this.topics.page(device, q, now);
  }
  adminTopics(p: Record<string, unknown>, now: number) {
    return this.topics.list(p, now);
  }
  adminTopic(p: Record<string, unknown>) {
    return this.topics.detail(p);
  }
  mutateTopic(a: string, p: Record<string, unknown>, now: number) {
    return this.topics.mutate(a, p, now);
  }
  saveState(
    credential: string,
    p: Record<string, unknown>,
    digest: string,
    now: number,
  ) {
    return this.storage.transactionSync(() =>
      this.states.save(this.sender(credential), p, digest, now),
    );
  }
  statePage(device: string, q: URLSearchParams, now: number) {
    this.active(device);
    return this.states.page(device, q, now);
  }
  stateMeta(credential: string, topic: string) {
    return this.states.meta(this.sender(credential), topic);
  }
  adminStates(p: Record<string, unknown>, now: number) {
    return this.states.adminList(p, now);
  }
  adminState(p: Record<string, unknown>) {
    return this.states.adminDetail(p);
  }
  clearAdminState(p: Record<string, unknown>, now: number) {
    return this.states.adminClear(p, now);
  }
}
