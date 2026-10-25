"""Offline regression checks: python -m unittest discover -s examples/structured."""
import unittest
from send_state import should_send

class SenderPowerTest(unittest.TestCase):
    def setUp(self):
        self.previous = {"sent_at": 100, "value": 40, "status": "running", "unknown_total": False}
    def test_running_samples_are_coalesced_until_time_and_change_thresholds(self):
        self.assertFalse(should_send(self.previous,"download",50,"running",False,102))
        self.assertFalse(should_send(self.previous,"download",40.2,"running",False,110))
        self.assertTrue(should_send(self.previous,"download",41,"running",False,110))
    def test_terminals_pause_and_clock_rollback_are_never_suppressed(self):
        for status in ("completed","failed","cancelled","paused"):
            self.assertTrue(should_send(self.previous,"download",40,status,False,101))
        self.assertTrue(should_send(self.previous,"download",40,"running",False,99))
    def test_idle_and_unknown_total_refresh_before_freshness_expires(self):
        self.assertTrue(should_send(self.previous,"download",40,"running",False,400))
        self.previous["unknown_total"]=True
        self.assertFalse(should_send(self.previous,"download",40,"running",True,110))
        self.assertTrue(should_send(self.previous,"download",40,"running",True,400))
    def test_other_topics_and_first_samples_are_not_suppressed(self):
        self.assertTrue(should_send(self.previous,"quota",40,"running",False,101))
        self.assertTrue(should_send({},"download",40,"running",False,101))

if __name__ == "__main__":
    unittest.main()
