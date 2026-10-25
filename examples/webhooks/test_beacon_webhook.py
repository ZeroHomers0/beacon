import hashlib
import hmac
import io
import json
import unittest
import urllib.error
from unittest.mock import Mock
from beacon_webhook import BeaconSender, WebhookAdapter, MAX_BODY


class Sender:
    def __init__(self):
        self.calls = []
    def send(self, value, key):
        self.calls.append((value, key))


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.sender = Sender()
        self.adapter = WebhookAdapter(self.sender, "github-test-secret", "grafana-test-token")
    def github(self, raw, event="push"):
        return {"X-Hub-Signature-256": "sha256=" + hmac.new(b"github-test-secret", raw, hashlib.sha256).hexdigest(),
                "X-GitHub-Event": event}
    def test_rejects_unauthenticated_and_disabled_providers(self):
        self.assertEqual(401, self.adapter.handle("/github", {}, b"{}")[0])
        self.assertEqual(401, self.adapter.handle("/grafana", {}, b"{}")[0])
        self.assertEqual(404, WebhookAdapter(self.sender).handle("/github", {}, b"{}")[0])
        self.assertFalse(self.sender.calls)
    def test_duplicate_signed_payload_keeps_a_stable_bounded_idempotency_key(self):
        value = {"repository": {"full_name": "example/repo"}, "ref": "refs/heads/main", "commits": [{"message": "fix"}]}
        first = json.dumps(value).encode()
        second = json.dumps(value, separators=(",", ":")).encode()
        for raw in (first, second):
            self.assertEqual(202, self.adapter.handle("/github", self.github(raw), raw)[0])
        self.assertEqual(self.sender.calls[0][1], self.sender.calls[1][1])
        self.assertLessEqual(len(self.sender.calls[0][1]), 128)
        self.assertNotIn("mode", self.sender.calls[0][0])
    def test_rejects_duplicate_json_keys_and_non_finite_values(self):
        for raw in (b'{"status":"firing","status":"resolved"}', b'{"status":"firing","x":NaN}'):
            self.assertEqual(400, self.adapter.handle("/grafana", {"Authorization": "Bearer grafana-test-token"}, raw)[0])
        self.assertFalse(self.sender.calls)
    def test_body_limits_and_unsupported_events_do_not_publish(self):
        self.assertEqual(413, self.adapter.handle("/github", {}, b"x" * (MAX_BODY + 1))[0])
        raw = b"{}"
        self.assertEqual(202, self.adapter.handle("/github", self.github(raw, "ping"), raw)[0])
        self.assertFalse(self.sender.calls)
    def test_grafana_summarizes_and_recovers(self):
        raw = json.dumps({"status": "resolved", "title": "Service", "alerts": [{"annotations": {"summary": "healthy"}}]}).encode()
        self.assertEqual(202, self.adapter.handle("/grafana", {"Authorization": "Bearer grafana-test-token"}, raw)[0])
        self.assertIn("已恢复", self.sender.calls[0][0]["body"])
    def test_workflow_ignores_unsafe_links(self):
        raw = json.dumps({"action": "completed", "workflow_run": {"name": "test", "conclusion": "failure", "html_url": "http://unsafe.invalid"}}).encode()
        self.assertEqual(202, self.adapter.handle("/github", self.github(raw, "workflow_run"), raw)[0])
        self.assertNotIn("actions", self.sender.calls[0][0])
    def test_failure_response_does_not_expose_payloads_or_credentials(self):
        self.sender.send = Mock(side_effect=OSError("secret-request-body"))
        raw = b'{"status":"firing"}'
        status, value = self.adapter.handle("/grafana", {"Authorization": "Bearer grafana-test-token"}, raw)
        self.assertEqual(502, status)
        self.assertNotIn("secret", str(value))
    def test_sender_retries_same_identity_but_not_permanent_failures(self):
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.side_effect = [urllib.error.HTTPError("https://example.invalid", 429, "limited", {"Retry-After": "2"}, io.BytesIO()), response]
        sleeps = []
        sender = BeaconSender("https://example.invalid", "a" * 64, opener=opener, sleep=sleeps.append)
        sender.send({"body": "test"}, "test-key")
        self.assertEqual([2], sleeps)
        first, second = [call.args[0] for call in opener.open.call_args_list]
        self.assertEqual(first.get_header("Idempotency-key"), second.get_header("Idempotency-key"))
        opener.open.reset_mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 403, "forbidden", {}, io.BytesIO())
        with self.assertRaises(ValueError):
            sender.send({"body": "test"}, "test-key")
        self.assertEqual(1, opener.open.call_count)
    def test_sender_rejects_insecure_or_credential_bearing_urls(self):
        for url in ("http://example.invalid", "https://user:password@example.invalid", "https://example.invalid?token=abc"):
            with self.assertRaises(ValueError):
                BeaconSender(url, "a" * 64)
    def test_sender_does_not_retry_before_a_long_cooldown(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 429, "limited", {"Retry-After": "120"}, io.BytesIO())
        sleeps = []
        sender = BeaconSender("https://example.invalid", "a" * 64, opener=opener, sleep=sleeps.append)
        with self.assertRaises(ValueError):
            sender.send({"body": "test"}, "test-key")
        self.assertEqual(1, opener.open.call_count)
        self.assertEqual([], sleeps)


if __name__ == "__main__":
    unittest.main()
