#!/usr/bin/env python3
"""External, dependency-free GitHub/Grafana -> Beacon event adapter. Never creates topics."""
from __future__ import annotations
import hashlib
import hmac
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

MAX_BODY = 256 * 1024


def secret(name: str) -> str:
    filename = os.environ.get(name + "_FILE")
    if filename:
        raw = Path(filename).read_bytes()
        if len(raw) > 4096:
            raise ValueError("secret file too large")
        return raw.decode("utf-8").rstrip("\r\n")
    return os.environ.get(name, "")


def unique_object(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def clean(value, limit=512):
    if not isinstance(value, str):
        return ""
    return "".join(c for c in value if c == "\n" or ord(c) >= 32)[:limit]


def https_url(value):
    if not isinstance(value, str) or len(value) > 2048 or any(c.isspace() for c in value):
        return None
    parsed = urllib.parse.urlsplit(value)
    return value if parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password else None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BeaconSender:
    def __init__(self, base_url, key, *, opener=None, sleep=time.sleep):
        parsed = urllib.parse.urlsplit(base_url)
        if not https_url(base_url) or parsed.query or parsed.fragment:
            raise ValueError("BEACON_URL must be an HTTPS base URL")
        if not re.fullmatch(r"[a-fA-F0-9]{64}", key):
            raise ValueError("invalid Beacon push credential")
        self.url = base_url.rstrip("/") + "/api/v1/push"
        self.key = key
        self.opener = opener or urllib.request.build_opener(NoRedirect())
        self.sleep = sleep

    def send(self, payload, identity):
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(raw) > 512 * 1024:
            raise ValueError("output too large")
        for attempt in range(3):
            request = urllib.request.Request(self.url, raw, {
                "Authorization": "Bearer " + self.key,
                "Content-Type": "application/json",
                "Idempotency-Key": identity,
            }, method="POST")
            try:
                with self.opener.open(request, timeout=10) as response:
                    if not 200 <= response.status < 300:
                        raise ValueError("unexpected Beacon response")
                    # A bounded read releases the HTTP response without collecting message bodies.
                    response.read(8192)
                    return
            except urllib.error.HTTPError as error:
                if error.code != 429 and not 500 <= error.code < 600:
                    raise ValueError("Beacon rejected the event") from None
                retry = error.headers.get("Retry-After", "") if error.headers else ""
                error.close()
                delay = 2 ** attempt
                if retry:
                    try:
                        delay = float(retry)
                    except ValueError:
                        try:
                            delay = (parsedate_to_datetime(retry) - datetime.now(timezone.utc)).total_seconds()
                        except (ValueError, TypeError, OverflowError):
                            pass
                if attempt == 2 or delay > 60:
                    # Let the provider retry later; never retry before a long server cooldown.
                    raise ValueError("Beacon delivery unavailable") from None
                self.sleep(max(1, delay))
            except (urllib.error.URLError, TimeoutError, OSError):
                if attempt == 2:
                    raise ValueError("Beacon delivery unavailable") from None
                self.sleep(2 ** attempt)


class WebhookAdapter:
    def __init__(self, sender, github_secret="", grafana_token=""):
        self.sender = sender
        self.github_secret = github_secret
        self.grafana_token = grafana_token

    def handle(self, path, headers, raw):
        if path not in ("/github", "/grafana"):
            return 404, {"error": "not_found"}
        if len(raw) > MAX_BODY:
            return 413, {"error": "payload_too_large"}
        if path == "/github":
            if not self.github_secret:
                return 404, {"error": "provider_disabled"}
            expected = "sha256=" + hmac.new(self.github_secret.encode(), raw, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(headers.get("X-Hub-Signature-256", ""), expected):
                return 401, {"error": "unauthorized"}
        else:
            if not self.grafana_token:
                return 404, {"error": "provider_disabled"}
            if not hmac.compare_digest(headers.get("Authorization", ""), "Bearer " + self.grafana_token):
                return 401, {"error": "unauthorized"}
        try:
            value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                               parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-finite JSON")))
            if not isinstance(value, dict):
                raise ValueError("object required")
            event = self.github(value, headers.get("X-GitHub-Event", "")) if path == "/github" else self.grafana(value)
            if event is None:
                return 202, {"status": "ignored"}
            canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
            # The signed payload, not an unsigned delivery header, defines the retry identity.
            identity = path[1:] + ":" + hashlib.sha256(canonical).hexdigest()
        except (ValueError, TypeError, UnicodeError, RecursionError, AttributeError):
            return 400, {"error": "invalid_payload"}
        try:
            self.sender.send(event, identity)
        except Exception:
            # Never return request payloads, provider secrets, exception URLs or Beacon credentials.
            return 502, {"error": "delivery_failed"}
        return 202, {"status": "accepted"}

    @staticmethod
    def github(value, kind):
        repo = value.get("repository", {})
        name = clean(repo.get("full_name"), 200) or "GitHub"
        url = None
        if kind == "workflow_run" and value.get("action") == "completed":
            run = value.get("workflow_run", {})
            conclusion = clean(run.get("conclusion"), 80) or "completed"
            title = "CI · " + name
            body = clean(run.get("name"), 200) + "\n结果：" + conclusion
            url = https_url(run.get("html_url"))
        elif kind == "push":
            title = "代码更新 · " + name
            commits = value.get("commits", [])
            if not isinstance(commits, list):
                raise ValueError("invalid commits")
            body = "分支：" + clean(value.get("ref"), 200) + "\n" + "\n".join(
                clean(item.get("message"), 400) for item in commits[:5] if isinstance(item, dict))
            url = https_url(value.get("compare"))
        else:
            return None
        result = {"title": title, "body": body.strip() or "GitHub事件", "group": "GitHub", "priority": 5}
        if url:
            result["actions"] = [{"title": "查看详情", "url": url}]
        return result

    @staticmethod
    def grafana(value):
        status = value.get("status")
        if status not in ("firing", "resolved"):
            raise ValueError("unsupported Grafana status")
        title = clean(value.get("title"), 240) or "Grafana告警"
        lines = ["状态：" + ("告警中" if status == "firing" else "已恢复")]
        if value.get("message"):
            lines.append(clean(value["message"], 4000))
        alerts = value.get("alerts", [])
        if not isinstance(alerts, list):
            raise ValueError("invalid alerts")
        for alert in alerts[:5]:
            if not isinstance(alert, dict):
                continue
            annotations = alert.get("annotations", {})
            lines.append(clean(annotations.get("summary"), 400))
        return {"title": title, "body": "\n".join(filter(None, lines)), "group": "Grafana", "priority": 5}


def serve(adapter, host="127.0.0.1", port=8090):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "-1"))
                if self.headers.get("Transfer-Encoding") or not 0 <= length <= MAX_BODY:
                    self.send_error(413)
                    return
                if self.headers.get_content_type() != "application/json":
                    self.send_error(415)
                    return
                raw = self.rfile.read(length)
                if len(raw) != length:
                    self.send_error(400)
                    return
                status, value = adapter.handle(self.path, self.headers, raw)
                output = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(output)))
                self.end_headers()
                self.wfile.write(output)
            except (ValueError, TimeoutError, OSError):
                self.close_connection = True

    # Serial, low-volume example: no unbounded thread pool or background retry queue.
    HTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    github_secret, grafana_token = secret("GITHUB_WEBHOOK_SECRET"), secret("GRAFANA_WEBHOOK_TOKEN")
    if not github_secret and not grafana_token:
        raise SystemExit("Configure at least one provider secret")
    sender = BeaconSender(os.environ.get("BEACON_URL", ""), secret("BEACON_PUSH_KEY"))
    serve(WebhookAdapter(sender, github_secret, grafana_token),
          os.environ.get("WEBHOOK_HOST", "127.0.0.1"), int(os.environ.get("WEBHOOK_PORT", "8090")))
