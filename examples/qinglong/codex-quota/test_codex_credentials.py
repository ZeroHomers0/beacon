"""Offline credential compatibility tests; no login, refresh or push network calls."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("quota_sender", Path(__file__).with_name("codex_quota_to_beacon.py"))
quota = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quota)


def jwt(account="account-a", expires=None):
    claims = {"sub": "user-1", "exp": int(time.time()) + 3600 if expires is None else expires,
              "https://api.openai.com/auth": {"chatgpt_account_id": account}}
    return "header." + base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=") + ".signature"


class CredentialCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.cache = Path(self.directory.name) / "tokens.json"
        self.env = patch.dict(os.environ, {"CODEX_TOKEN_CACHE": str(self.cache), "CODEX_REFRESH": "0"}, clear=True)
        self.env.start()
    def tearDown(self):
        self.env.stop()
        self.directory.cleanup()
    def test_native_and_cliproxy_export_both_load_from_inline_json(self):
        for auth in ({"tokens": {"access_token": jwt(), "account_id": "account-a"}},
                     {"type": "codex", "access_token": jwt(), "account_id": "account-a"}):
            with patch.dict(os.environ, {"CODEX_AUTH_JSON": json.dumps(auth)}):
                loaded, path = quota.load_auth()
                self.assertIsNone(path)
                self.assertEqual(quota.ensure_token(loaded, path), quota._tokens(auth)["access_token"])
                self.assertEqual("account-a", quota._account_id(loaded))
    def test_bom_file_and_null_nested_tokens_accept_flat_fields(self):
        path = Path(self.directory.name) / "export.json"
        auth = {"type": "codex", "tokens": None, "access_token": jwt()}
        path.write_text(json.dumps(auth), encoding="utf-8-sig")
        with patch.dict(os.environ, {"CODEX_AUTH_JSON": str(path)}):
            loaded, source = quota.load_auth()
            self.assertEqual(path, source)
            self.assertEqual(auth["access_token"], quota.ensure_token(loaded, source))
    def test_flat_refresh_preserves_format_metadata_and_rotated_token(self):
        auth = {"type": "codex", "email": "fixture@example.invalid", "disabled": False,
                "access_token": jwt(), "refresh_token": "old-refresh", "account_id": "account-a"}
        path = Path(self.directory.name) / "export.json"
        new = jwt(expires=int(time.time()) + 7200)
        with patch.object(quota, "http_json", return_value=(200, {"access_token": new, "refresh_token": "new-refresh"}, {})):
            updated = quota.refresh_tokens(auth, path)
        saved = json.loads(path.read_text())
        self.assertNotIn("tokens", saved)
        self.assertEqual("new-refresh", saved["refresh_token"])
        self.assertEqual(auth["email"], saved["email"])
        self.assertFalse(saved["disabled"])
        self.assertTrue(saved["expired"].endswith("Z"))
        self.assertEqual(new, updated["access_token"])
    def test_native_refresh_keeps_nested_schema(self):
        auth = {"auth_mode": "chatgpt", "tokens": {"access_token": jwt(), "refresh_token": "old", "account_id": "account-a"}}
        path = Path(self.directory.name) / "auth.json"
        with patch.object(quota, "http_json", return_value=(200, {"access_token": jwt(), "refresh_token": "rotated"}, {})):
            quota.refresh_tokens(auth, path)
        saved = json.loads(path.read_text())
        self.assertEqual("rotated", saved["tokens"]["refresh_token"])
        self.assertNotIn("access_token", saved)
    def test_cache_does_not_mix_accounts_or_reuse_legacy_unscoped_cache(self):
        auth = {"type": "codex", "access_token": jwt(), "account_id": "account-a"}
        newer = {"tokens": {"access_token": jwt(expires=int(time.time()) + 7200), "account_id": "account-a"}, "scope": quota._cache_scope(auth)}
        self.cache.write_text(json.dumps(newer))
        self.assertEqual(newer["tokens"]["access_token"], quota._tokens(quota._apply_cached_tokens(auth))["access_token"])
        other = {"type": "codex", "access_token": jwt("account-b"), "account_id": "account-b"}
        self.assertEqual(other, quota._apply_cached_tokens(other))
        del newer["scope"]
        self.cache.write_text(json.dumps(newer))
        self.assertEqual(auth, quota._apply_cached_tokens(auth))
    def test_expiry_refresh_then_401_retry_uses_rotated_refresh_token(self):
        auth = {"type": "codex", "access_token": jwt(expires=1), "refresh_token": "first", "account_id": "account-a"}
        with patch.dict(os.environ, {"CODEX_REFRESH": "1"}), patch.object(quota, "http_json", side_effect=[
            (200, {"access_token": jwt(), "refresh_token": "second"}, {}),
            (200, {"access_token": jwt(), "refresh_token": "third"}, {}),
        ]) as network, patch.object(quota, "fetch_usage", side_effect=[(401, {}), (200, {"rate_limit": {"primary_window": {"used_percent": 10}}})]) as usage:
            result = quota.read_usage(auth, None)
        self.assertIn("rate_limit", result)
        self.assertEqual("first", network.call_args_list[0].kwargs["form"]["refresh_token"])
        self.assertEqual("second", network.call_args_list[1].kwargs["form"]["refresh_token"])
        self.assertEqual("account-a", usage.call_args_list[0].args[1])
    def test_missing_access_can_refresh_without_requiring_codex_cli_login(self):
        auth = {"type": "codex", "refresh_token": "refresh-only"}
        token=jwt()
        with patch.dict(os.environ, {"CODEX_REFRESH": "1"}), patch.object(quota, "http_json", return_value=(200, {"access_token": token}, {})):
            self.assertEqual(token, quota.ensure_token(auth, None))
    def test_readonly_refresh_only_source_keeps_same_scope_across_rotations(self):
        original = {"type": "codex", "refresh_token": "original-refresh"}
        initial = jwt()
        future = jwt(expires=int(time.time()) + 7200)
        with patch.dict(os.environ, {"CODEX_REFRESH": "1"}):
            with patch.object(quota, "http_json", return_value=(200, {"access_token": initial, "refresh_token": "rotated-1"}, {})):
                self.assertEqual(initial, quota.ensure_token(dict(original), None))
            with patch.object(quota.time, "time", return_value=time.time()+4000), patch.object(quota, "http_json", return_value=(200, {"access_token": future, "refresh_token": "rotated-2"}, {})) as network:
                self.assertEqual(future, quota.ensure_token(dict(original), None))
                self.assertEqual("rotated-1", network.call_args.kwargs["form"]["refresh_token"])
            with patch.object(quota, "http_json") as network:
                self.assertEqual(future, quota.ensure_token(dict(original), None))
                network.assert_not_called()

    def test_malformed_or_other_provider_is_rejected_without_exposing_credentials(self):
        for auth in ({"tokens": []}, {"type": "other", "access_token": "never-print-secret"}):
            with patch.object(quota, "log") as output, self.assertRaises(SystemExit):
                quota.ensure_token(auth, None)
            self.assertNotIn("never-print-secret", str(output.call_args_list))

if __name__ == "__main__":
    unittest.main()
