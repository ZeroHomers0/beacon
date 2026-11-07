/** Wire response shapes shared by ports and the SQL implementation. */
export interface TopicRecord extends Record<string, unknown> {
  device_id: string;
  application_id: string;
  topic_key: string;
  name: string;
  config_version: number;
  created_at_ms: number;
  updated_at_ms: number;
  deleted: boolean;
}
export interface TopicResult {
  topic: TopicRecord;
}
export interface TopicSummary extends TopicRecord {
  title: string;
  state: string;
  revision: number;
  source_name: string;
}
export interface AdminPage<T> {
  topics: T[];
  total: number;
  has_more: boolean;
  next_offset: number;
}
export interface SyncPage<T> {
  server_id: string;
  topics: T[];
  has_more: boolean;
  next_cursor?: string;
  snapshot_token?: string;
  page_cursor?: string;
}
export interface StateRecord extends Record<string, unknown> {
  topic_key: string;
  revision: number;
  application_id: string;
  deleted: boolean;
  accepted_at_ms: number;
  expires_at_ms: number;
}
