"""Offline tests for the four widget values and reset-time fallbacks."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("quota_values", Path(__file__).with_name("codex_quota_to_beacon.py"))
quota = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quota)


class WidgetValuesTest(unittest.TestCase):
    def build(self, primary, secondary=None):
        with patch.object(quota.time, "time", return_value=1800000000):
            return quota.build_state({"rate_limit": {"primary_window": primary, "secondary_window": secondary}})[2]

    def test_four_values_have_compact_labels_and_valid_ranges(self):
        blocks = self.build({"used_percent": 60, "reset_after_seconds": 7200},
                            {"used_percent": 20, "reset_after_seconds": 259200})
        progress = [b for b in blocks if b["kind"] == "progress"]
        self.assertEqual([b["id"] for b in progress], ["session", "week", "session-reset-time", "week-reset-time"])
        self.assertEqual([b["label"] for b in progress], ["5h 额度", "7d 额度", "5h 重置", "7d 重置"])
        self.assertEqual([(b["value"], b["unit"]) for b in progress], [(40, "%"), (80, "%"), (2, "h"), (3, "d")])
        for b in progress:
            self.assertTrue(0 <= b["value"] <= b["max"])
        self.assertEqual(len({b["id"] for b in blocks}), len(blocks))
        self.assertLessEqual(len(blocks), 16)

    def test_absolute_reset_takes_precedence(self):
        blocks = self.build({"used_percent": 10, "reset_at": 1800000061, "reset_after_seconds": 999})
        self.assertEqual(blocks[1]["value"], 2)
        self.assertEqual(blocks[1]["unit"], "min")

    def test_relative_reset_produces_precise_target(self):
        blocks = self.build({"used_percent": 10, "reset_after_seconds": 60})
        countdown = next(b for b in blocks if b["kind"] == "countdown")
        self.assertEqual(countdown["target_at_ms"], 1800000060000)

    def test_missing_reset_does_not_invent_time(self):
        blocks = self.build({"used_percent": 10})
        self.assertEqual([b["id"] for b in blocks], ["session"])

    def test_expired_reset_does_not_restore_quota(self):
        blocks = self.build({"used_percent": 90, "reset_at": 1799999999})
        self.assertEqual(blocks[0]["value"], 10)
        self.assertEqual(blocks[1]["value"], 0)

    def test_units_boundaries_and_rounding_stay_in_range(self):
        for seconds, unit in ((1, "min"), (3599, "min"), (3600, "h"), (86399, "h"), (86400, "d")):
            with self.subTest(seconds=seconds):
                blocks = self.build({"used_percent": 0, "reset_after_seconds": seconds})
                self.assertEqual(blocks[1]["unit"], unit)
                self.assertLessEqual(blocks[1]["value"], blocks[1]["max"])

    def test_invalid_numbers_do_not_reach_payload(self):
        for value in (True, float("nan"), float("inf"), "20"):
            self.assertEqual(self.build({"used_percent": value}), [])
        blocks = self.build({"used_percent": 10, "reset_at": float("nan"), "reset_after_seconds": float("inf")})
        self.assertEqual(len(blocks), 1)


if __name__ == "__main__":
    unittest.main()
