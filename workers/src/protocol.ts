// Wire contract versions. A single source of truth on the Worker side so a bump
// is one edit rather than a hunt for integer literals, and so the shared number
// matches the Go server and the Android client.
export const HTTP_PROTOCOL = 1;
export const REALTIME_PROTOCOL = 2;
export const REALTIME_ENVELOPE_PROTOCOL = 1;
export const DELIVERY_RETENTION_PROTOCOL = 1;
export const RECEPTION_CONTROL = 1;
export const ADMIN_PROTOCOL = 1;

export const STREAM_PATH = "/api/v1/realtime/stream";

// The client is expected to accept a range, not an exact match: these are the
// bounds this Worker implements, advertised for forward-compatible clients.
export const SUPPORTED_HTTP_PROTOCOLS = { min: 1, max: HTTP_PROTOCOL };

export const STATE_PROTOCOL = 1;
