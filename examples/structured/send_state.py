"""Beacon state sender. Credentials come only from BEACON_URL/BEACON_PUSH_KEY.

Examples:
  python send_state.py quota account-a --value 24
  python send_state.py download task-123 --value 40 --status running
  python send_state.py disk nas
  python send_state.py metrics server
Writes a local revision journal; reuse --journal after restart. One writer per topic.
"""
import argparse
import hashlib
import json
import os
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request


def request(base, key, path, payload=None, request_key=None):
    encoded = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, encoded, headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    if request_key:
        req.add_header("Idempotency-Key",request_key)
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 5:
                raise RuntimeError(f"Beacon HTTP {error.code}") from None
            delay = error.headers.get("Retry-After", "")
            time.sleep(min(120, int(delay) if delay.isdigit() else 2 ** attempt))
        except (TimeoutError, urllib.error.URLError):
            if attempt == 5:
                raise RuntimeError("Beacon connection failed") from None
            time.sleep(2 ** attempt)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=["quota", "download", "disk", "metrics"])
    parser.add_argument("topic")
    parser.add_argument("--value", type=float, default=24)
    parser.add_argument("--status", choices=["running", "paused", "completed", "failed", "cancelled"], default="running")
    parser.add_argument("--journal", type=pathlib.Path, default=pathlib.Path("beacon-revisions.json"))
    parser.add_argument("--unknown-total", action="store_true")
    args = parser.parse_args()
    base = os.environ["BEACON_URL"].rstrip("/")
    key = os.environ["BEACON_PUSH_KEY"]
    capability = request(base, key, "/api/v1/push-capabilities")
    if capability.get("state_protocol") != 1:
        raise RuntimeError("Server does not support state protocol 1")
    topic = args.kind + ":" + args.topic
    journal = json.loads(args.journal.read_text(encoding="utf-8")) if args.journal.exists() else {}
    if topic in journal and "event_pending" in journal[topic]:
        entry=journal[topic]
        request(base,key,"/api/v1/push",entry["event_pending"],entry["event_key"])
    # Complete a request left uncertain by a previous process before allocating a revision.
    if topic in journal and "pending" in journal[topic]:
        request(base, key, "/api/v1/push", journal[topic]["pending"])
    meta = request(base, key, "/api/v1/states/meta?topic_key=" + urllib.parse.quote(topic, safe=""))
    revision = max(journal.get(topic, {}).get("revision", 0), meta["revision"]) + 1
    payload = {"mode": "state", "schema_version": 1, "topic_key": topic, "revision": revision, "observed_at_ms": int(time.time()*1000), "fresh_for_seconds": 600, "title": args.topic, "body": f"{args.kind}: {args.value}%"}
    progress = lambda name, value: {"kind": "progress", "id": name, "label": name, "value": value, "max": 100, "unit": "%"}
    if args.kind == "quota":
        payload["blocks"] = [progress("short", args.value), progress("long", 62)]
    elif args.kind == "download":
        payload.update(state_type="task", status=args.status, blocks=[{"kind":"progress","id":"download","label":"下载进度","indeterminate":True}] if args.unknown_total else [progress("download", args.value)])
    elif args.kind == "disk":
        payload["blocks"] = [{"kind": "segments", "id": "disk", "label": "存储分布", "total": 100, "unit": "GB", "items": [{"id":"images","label":"图片","value":30},{"id":"videos","label":"视频","value":40},{"id":"other","label":"其他","value":10}]}]
    else:
        payload["blocks"] = [progress(name, value) for name,value in [("cpu",24),("memory",62),("disk",76),("quota",89)]]
    def save(entry):
        journal[topic] = entry
        temporary = args.journal.with_suffix(".tmp")
        temporary.write_text(json.dumps(journal, ensure_ascii=False), encoding="utf-8")
        temporary.replace(args.journal)
    save({"revision": revision, "pending": payload})
    result = request(base, key, "/api/v1/push", payload)
    save({"revision": revision})
    if args.kind == "download" and args.status in ("completed", "failed", "cancelled"):
        event={"title":args.topic,"body":"下载任务："+args.status,"priority":8 if args.status=="failed" else 5}
        event_key=hashlib.sha256(f"{topic}:{revision}:{args.status}".encode()).hexdigest()
        save({"revision":revision,"event_pending":event,"event_key":event_key})
        request(base,key,"/api/v1/push",event,event_key)
        save({"revision":revision})
    print(f"State accepted: revision {result['revision']}")


if __name__ == "__main__":
    main()
