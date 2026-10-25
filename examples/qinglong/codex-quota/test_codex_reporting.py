"""Offline reporting cadence, persistent retry and destination isolation tests."""
import copy
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("quota_reporting", Path(__file__).with_name("codex_quota_to_beacon.py"))
quota = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quota)


class ReportingTest(unittest.TestCase):
    def setUp(self):
        self.saved = {}
        self.now = 1800000000
        self.data = {"rate_limit": {"primary_window": {"used_percent": 20, "reset_at": self.now + 12000},
                                    "secondary_window": {"used_percent": 30, "reset_at": self.now + 500000}}}
        self.patches = [
            patch.dict(os.environ, {}, clear=True),
            patch.object(quota.time, "time", side_effect=lambda: self.now),
            patch.object(quota, "load_auth", return_value=({"access_token": "fake-token"}, None)),
            patch.object(quota, "read_usage", side_effect=lambda *_: copy.deepcopy(self.data)),
            patch.object(quota, "_read_json", side_effect=lambda _: copy.deepcopy(self.saved)),
            patch.object(quota, "_write_json", side_effect=lambda _, value: self.saved.update(copy.deepcopy(value))),
            patch.object(quota, "http_json", return_value=(200, {"revision": 1}, {})),
            patch.object(quota, "log"),
        ]
        self.mocks = [p.start() for p in self.patches]
        self.addCleanup(lambda: [p.stop() for p in reversed(self.patches)])
        # Replace rather than update to reflect file writes dropping old keys.
        self.mocks[5].side_effect = self.save

    def save(self, _, value):
        self.saved = copy.deepcopy(value)

    def run_sender(self, key="push-key", topic="widget/test", dry=False):
        return quota._run("https://example.com", key, topic, dry)

    def test_same_display_skips_even_though_detail_time_text_changes(self):
        self.run_sender()
        calls = self.mocks[6].call_count
        self.now += 1
        self.run_sender()
        self.assertEqual(calls, self.mocks[6].call_count)
        self.assertNotIn("pending", self.saved)

    def test_quota_and_reset_point_changes_report_immediately(self):
        self.run_sender()
        for field in ("used_percent", "reset_at"):
            before = self.mocks[6].call_count
            self.data["rate_limit"]["primary_window"][field] += 1
            self.run_sender()
            self.assertEqual(before + 2, self.mocks[6].call_count)

    def test_unchanged_quota_is_kept_fresh_at_thirty_minutes(self):
        # No reset data means all values remain unchanged over the whole interval.
        self.data = {"rate_limit": {"primary_window": {"used_percent": 20}}}
        self.run_sender()
        self.now += 1799
        self.run_sender()
        self.assertEqual(2, self.mocks[6].call_count)
        self.now += 1
        self.run_sender()
        self.assertEqual(4, self.mocks[6].call_count)

    def test_short_freshness_and_ttl_shorten_keepalive(self):
        self.assertEqual(30, quota._report_interval({"fresh_for_seconds": 60, "ttl": 100}))
        self.assertEqual(5, quota._report_interval({"ttl": 10}))

    def test_failures_persist_and_success_clears_backoff(self):
        self.mocks[3].side_effect = RuntimeError("offline")
        with self.assertRaises(RuntimeError):
            self.run_sender()
        self.assertEqual(self.now + 300, self.saved["retry_at"])
        reads = self.mocks[3].call_count
        self.now += 299
        self.run_sender()
        self.assertEqual(reads, self.mocks[3].call_count)
        self.now += 1
        with self.assertRaises(RuntimeError):
            self.run_sender()
        self.assertEqual(self.now + 600, self.saved["retry_at"])
        self.now += 600
        self.mocks[3].side_effect = lambda *_: self.data
        self.run_sender()
        self.assertEqual(0, self.saved["failures"])
        self.assertEqual(0, self.saved["retry_at"])

    def test_scope_change_cannot_skip_new_destination(self):
        self.run_sender()
        self.run_sender(key="different-key")
        self.run_sender(key="different-key", topic="widget/other")
        self.assertEqual(6, self.mocks[6].call_count)

    def test_clock_rollback_forces_fresh_report(self):
        self.data = {"rate_limit": {"primary_window": {"used_percent": 20}}}
        self.run_sender()
        self.now -= 60
        self.run_sender()
        self.assertEqual(4, self.mocks[6].call_count)

    def test_displayed_time_change_reports_even_with_same_quota(self):
        self.run_sender()
        self.now += 600
        self.run_sender()
        self.assertEqual(4, self.mocks[6].call_count)

    def test_skip_does_not_rewrite_journal(self):
        self.run_sender()
        writes = self.mocks[5].call_count
        self.now += 1
        self.run_sender()
        self.assertEqual(writes, self.mocks[5].call_count)

    def test_dry_run_does_not_write_or_post(self):
        with patch("builtins.print"):
            self.run_sender(dry=True)
        self.assertEqual({}, self.saved)
        self.mocks[6].assert_not_called()

    def test_long_retry_after_is_persisted_without_immediate_retry(self):
        def limited(*_):
            quota._retry_not_before = self.now + 7200
            raise SystemExit(2)
        self.mocks[3].side_effect = limited
        with self.assertRaises(SystemExit):
            self.run_sender()
        self.assertEqual(self.now + 7200, self.saved["retry_at"])

    def test_failed_push_keeps_pending_and_does_not_cache_success(self):
        self.mocks[6].side_effect = [(200, {"revision": 1}, {}), (500, {}, {})]
        with self.assertRaises(SystemExit):
            self.run_sender()
        self.assertIn("pending", self.saved)
        self.assertNotIn("fingerprint", self.saved)


class RetryAfterTest(unittest.TestCase):
    def test_seconds_http_date_and_invalid_headers(self):
        with patch.object(quota.time, "time", return_value=0):
            self.assertEqual(7200, quota._retry_after("7200"))
            self.assertEqual(7200, quota._retry_after("Thu, 01 Jan 1970 02:00:00 GMT"))
            self.assertEqual(10, quota._retry_after("invalid"))

    def test_429_does_not_sleep_or_retry_in_process(self):
        with patch.object(quota.request, "urlopen", side_effect=HTTPError("https://example.com", 429, "limited", {"Retry-After": "7200"}, None)) as call, patch.object(quota.time, "sleep") as sleep:
            self.assertEqual(429, quota.http_json("https://example.com")[0])
            self.assertEqual(1, call.call_count)
            sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
