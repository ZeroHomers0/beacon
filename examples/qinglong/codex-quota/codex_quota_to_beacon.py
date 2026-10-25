#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# cron: */30 * * * *
# */30 * * * * codex_quota_to_beacon.py
# new Env('Beacon Codex 额度');
"""Codex 额度 -> Beacon 数据主题（青龙面板定时任务）

每 30 分钟读取一次 Codex 的五小时窗口与七天窗口额度，作为**完整快照**上报到
Beacon 数据主题，由手机桌面小组件展示。

用法（青龙面板）：
    1. 先在 App「服务器管理 → 数据主题」创建主题，例如 widget/codex-quota
    2. 把本文件放到青龙的脚本目录，例如 /ql/data/scripts/codex-quota/codex_quota_to_beacon.py
    3. 「环境变量」里添加 BEACON_BASE_URL、BEACON_PUSH_KEY，以及下面的 CODEX_AUTH_JSON
    4. 定时任务命令填 `task codex-quota/codex_quota_to_beacon.py`，定时规则填 `*/30 * * * *`

本脚本只依赖 Python 标准库（urllib），青龙镜像无需安装任何依赖。

环境变量：
    BEACON_BASE_URL      必填，Beacon 服务地址，例如 https://push.gzautobot.top
    BEACON_PUSH_KEY      必填，设备推送密钥或应用令牌
    CODEX_AUTH_JSON      必填，Codex 凭据：auth.json 的路径，或直接粘贴整段 JSON 内容
    CODEX_TOKEN_CACHE    可选，刷新后的令牌缓存文件，默认脚本同目录
    CODEX_REFRESH        可选，1/true 允许用 refresh_token 自动续期，默认开启
    CODEX_USAGE_URL      可选，额度接口，默认 https://chatgpt.com/backend-api/wham/usage
    CODEX_TOKEN_URL      可选，令牌刷新接口，默认 https://auth.openai.com/oauth/token
    CODEX_CLIENT_ID      可选，刷新令牌用的 OAuth client_id，一般不用改
    CODEX_ACCOUNT_ID     可选，工作区/组织账号 ID，只有多账号时才需要带
    TOPIC_KEY            可选，数据主题标识，默认 widget/codex-quota
    FRESH_FOR_SECONDS    可选，多旧算过期（秒），默认 3600（两倍采集间隔）
    WARN_PERCENT         可选，已用比例到达该值标黄，默认 70
    CRITICAL_PERCENT     可选，已用比例到达该值标红，默认 90
    BEACON_TTL           可选，数据有效期（秒），默认 604800；0 表示服务端默认
    BEACON_GROUP         可选，消息分组，默认 codex
    BEACON_PRIORITY      可选，优先级 0-10，默认 0（数据上报，不打扰）
    STATE_FILE           可选，revision 日志路径，默认脚本同目录
    LOCK_FILE            可选，运行锁路径，默认脚本同目录；防止两次运行重叠
    DRY_RUN              可选，只打印将要发送的内容，不真正上报，1/true 开启

退出码：0 成功/无需更新，1 配置错误，2 额度拉取失败，3 上报失败
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import NoReturn
from urllib import error, parse, request

SCRIPT_DIR = Path(__file__).resolve().parent
USER_AGENT = "beacon-qinglong-codex-quota/1.0"
DEFAULT_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
DEFAULT_TOKEN_URL = "https://auth.openai.com/oauth/token"
DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
# access_token 有效期内提前这么多秒续期，避免边界上刚好过期。
REFRESH_MARGIN_SECONDS = 300
# 锁文件超过这个时间就认为持有者已经死了，可以接管。
STALE_LOCK_SECONDS = 600
TITLE = "Codex 额度"


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _flag(name: str, default: bool = False) -> bool:
    raw = _env(name).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "y", "on"}


def _int_env(name: str, default: int, low: int | None = None, high: int | None = None) -> int:
    raw = _env(name)
    if not raw:
        value = default
    else:
        try:
            value = int(raw)
        except ValueError:
            log(f"{name}={raw!r} 不是整数，改用默认值 {default}")
            value = default
    if low is not None and value < low:
        value = low
    if high is not None and value > high:
        value = high
    return value


def log(message: str) -> None:
    stamp = datetime.now().strftime("%H:%M:%S")
    print(f"[codex-quota {stamp}] {message}", flush=True)


def fail(code: int, message: str) -> NoReturn:
    log(f"失败：{message}")
    raise SystemExit(code)


def http_json(
    url: str,
    *,
    method: str = "GET",
    headers: dict | None = None,
    form: dict | None = None,
    payload: dict | None = None,
    timeout: float = 20.0,
    retries: int = 2,
) -> tuple[int, dict, dict]:
    """返回 (状态码, JSON 响应体, 响应头)；网络错误与 5xx 自动重试，4xx 不重试。

    User-Agent 必须显式设置：Beacon 的 Cloudflare 会以 1010 拦截 Python 默认 UA。
    """
    body = None
    if form is not None:
        body = parse.urlencode(form).encode("utf-8")
    elif payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_error = ""
    next_delay = 0
    for attempt in range(retries + 1):
        if attempt:
            delay = next_delay or min(2**attempt, 8)
            next_delay = 0
            log(f"第 {attempt + 1} 次重试，{delay}s 后继续：{last_error}")
            time.sleep(delay)
        req = request.Request(url, data=body, method=method)
        req.add_header("User-Agent", USER_AGENT)
        req.add_header("Accept", "application/json")
        if form is not None:
            req.add_header("Content-Type", "application/x-www-form-urlencoded")
        elif body is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with request.urlopen(req, timeout=timeout) as resp:
                return resp.status, _decode(resp.read()), dict(resp.headers.items())
        except error.HTTPError as exc:
            data = _decode(exc.read())
            # 429 按 Retry-After 等待；其余 4xx 是请求本身的问题，重试没有意义。
            if exc.code == 429 and attempt < retries:
                next_delay = _retry_after(exc.headers.get("Retry-After"), default=10)
                last_error = f"HTTP 429 被限流，等待 {next_delay}s"
                continue
            if 500 <= exc.code < 600 and attempt < retries:
                last_error = f"HTTP {exc.code} {data or ''}".strip()
                continue
            return exc.code, data, dict(exc.headers.items())
        except (error.URLError, TimeoutError, OSError) as exc:
            last_error = f"{type(exc).__name__}: {exc}"
            if attempt < retries:
                continue
            raise RuntimeError(last_error) from exc
    raise RuntimeError(last_error or "unknown error")


def _decode(raw: bytes) -> dict:
    if not raw:
        return {}
    text = raw.decode("utf-8", errors="replace").strip()
    try:
        value = json.loads(text)
    except ValueError:
        return {"_raw": text[:2000]}
    return value if isinstance(value, dict) else {"_raw": value}


def _retry_after(value: str | None, default: int = 10) -> int:
    if not value:
        return default
    try:
        return max(1, min(int(value), 60))
    except ValueError:
        return default


# ---------------------------------------------------------------- 凭据与续期

def _jwt_exp(token: str) -> int:
    """读出 JWT 的 exp（秒）；格式异常返回 0，交给服务端 401 再决定。"""
    parts = token.split(".")
    if len(parts) < 2:
        return 0
    segment = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        claims = json.loads(base64.urlsafe_b64decode(segment.encode()).decode("utf-8"))
    except (ValueError, TypeError):
        return 0
    value = claims.get("exp")
    return int(value) if isinstance(value, (int, float)) else 0


def _cache_path() -> Path:
    custom = _env("CODEX_TOKEN_CACHE")
    return Path(custom).expanduser() if custom else SCRIPT_DIR / ".codex_quota_token.json"


def _state_path() -> Path:
    custom = _env("STATE_FILE")
    return Path(custom).expanduser() if custom else SCRIPT_DIR / ".codex_quota_state.json"


def _lock_path() -> Path:
    custom = _env("LOCK_FILE")
    return Path(custom).expanduser() if custom else SCRIPT_DIR / ".codex_quota.lock"


class RunLock:
    """避免同一容器里两次运行重叠：并发上报会互相顶掉版本号，还可能重复刷新令牌。"""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.held = False

    def __enter__(self) -> "RunLock":
        for _ in range(3):
            try:
                descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                # 上一次进程被强杀会留下锁文件；超过 STALE_LOCK_SECONDS 视为失效并接管。
                try:
                    age = time.time() - self.path.stat().st_mtime
                except OSError:
                    age = 0.0
                if age < STALE_LOCK_SECONDS:
                    log(f"另一个实例正在运行（锁文件 {self.path}，{int(age)}s 前创建），本次跳过")
                    raise SystemExit(0)
                log(f"锁文件已存在 {int(age)}s，判定为残留并接管")
                try:
                    self.path.unlink()
                except OSError:
                    pass
                continue
            os.write(descriptor, str(os.getpid()).encode())
            os.close(descriptor)
            self.held = True
            return self
        log(f"无法获取锁 {self.path}，本次跳过")
        raise SystemExit(0)

    def __exit__(self, *_exc) -> None:
        if self.held:
            try:
                self.path.unlink()
            except OSError:
                pass


def _read_json(path: Path) -> dict | None:
    # utf-8-sig：用户在 Windows 记事本里另存过的 auth.json 可能带 BOM。
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def _write_json(path: Path, value: dict) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    try:
        os.chmod(temporary, 0o600)
    except OSError:
        pass
    os.replace(temporary, path)


def load_auth() -> tuple[dict, Path | None]:
    """读取 Codex 凭据，返回 (auth 字典, 可回写路径)。支持路径或整段 JSON。"""
    raw = _env("CODEX_AUTH_JSON")
    if not raw:
        fail(1, "缺少环境变量 CODEX_AUTH_JSON（auth.json 路径，或整段 JSON）")
    if raw.startswith("{"):
        try:
            auth = json.loads(raw)
        except ValueError:
            fail(1, "CODEX_AUTH_JSON 看起来是 JSON，但解析失败")
        if not isinstance(auth, dict):
            fail(1, "CODEX_AUTH_JSON 里的 JSON 不是对象")
        return auth, None
    path = Path(raw).expanduser()
    auth = _read_json(path)
    if auth is None:
        fail(1, f"读不到 Codex 凭据：{path}（检查路径与文件权限）")
    return auth, path


def _apply_cached_tokens(auth: dict) -> dict:
    """缓存里的令牌比凭据文件新时优先使用，避免各进程各刷各的。"""
    cached = _read_json(_cache_path())
    if not cached:
        return auth
    fresh = ((cached.get("tokens") or {}).get("access_token") or "").strip()
    if not fresh:
        return auth
    tokens = dict(auth.get("tokens") or {})
    current = _jwt_exp(tokens.get("access_token") or "")
    if _jwt_exp(fresh) > current:
        tokens.update({k: v for k, v in cached["tokens"].items() if v})
        auth = dict(auth, tokens=tokens)
        log("使用缓存中的已续期令牌")
    return auth


def refresh_tokens(auth: dict, auth_path: Path | None) -> dict:
    """用 refresh_token 换新令牌，并尽力回写凭据文件与缓存。"""
    tokens = dict(auth.get("tokens") or {})
    refresh_token = (tokens.get("refresh_token") or "").strip()
    if not refresh_token:
        fail(1, "auth.json 里没有 refresh_token，无法自动续期；请重新 `codex login` 并更新凭据")
    log("access_token 已过期或即将过期，尝试刷新")
    status, data, _ = http_json(
        _env("CODEX_TOKEN_URL", DEFAULT_TOKEN_URL),
        method="POST",
        form={
            "grant_type": "refresh_token",
            "client_id": _env("CODEX_CLIENT_ID", DEFAULT_CLIENT_ID),
            "refresh_token": refresh_token,
        },
        timeout=25.0,
        retries=1,
    )
    if status != 200 or not data.get("access_token"):
        detail = json.dumps(data, ensure_ascii=False)[:300]
        fail(2, f"刷新令牌失败：HTTP {status} {detail}；请重新 `codex login` 并更新凭据")
    tokens["access_token"] = data["access_token"]
    # 服务端可能轮换 refresh_token：新的一定要落盘，否则下次就刷不动了。
    if data.get("refresh_token"):
        tokens["refresh_token"] = data["refresh_token"]
    if data.get("id_token"):
        tokens["id_token"] = data["id_token"]
    updated = dict(auth, tokens=tokens, last_refresh=_utc_now())
    _persist(updated, auth_path)
    return updated


def _utc_now() -> str:
    """与 codex 自己写入的格式一致：UTC、毫秒精度、Z 结尾。"""
    now = datetime.now(timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}Z"


def _persist(auth: dict, auth_path: Path | None) -> None:
    saved = False
    if auth_path is not None:
        try:
            _write_json(auth_path, auth)
            saved = True
        except OSError as exc:
            log(f"回写 {auth_path} 失败（{exc}），改为只写缓存；请确认容器对该文件可写")
    tokens = auth.get("tokens") or {}
    if tokens.get("access_token"):
        try:
            _write_json(_cache_path(), {"tokens": tokens})
            saved = True
        except OSError as exc:
            log(f"写入令牌缓存失败：{exc}")
    if not saved:
        log("警告：刷新后的令牌没能落盘，下次运行可能需要重新登录")


def ensure_token(auth: dict, auth_path: Path | None) -> str:
    auth = _apply_cached_tokens(auth)
    token = ((auth.get("tokens") or {}).get("access_token") or "").strip()
    if not token:
        fail(1, "凭据里没有 tokens.access_token；请重新 `codex login`")
    expires_at = _jwt_exp(token)
    if _flag("CODEX_REFRESH", default=True) and expires_at and expires_at - time.time() < REFRESH_MARGIN_SECONDS:
        auth = refresh_tokens(auth, auth_path)
        token = auth["tokens"]["access_token"]
    return token


# ---------------------------------------------------------------- 额度接口

def fetch_usage(token: str) -> tuple[int, dict]:
    headers = {"Authorization": f"Bearer {token}"}
    account_id = _env("CODEX_ACCOUNT_ID")
    if account_id:
        headers["ChatGPT-Account-Id"] = account_id
    status, data, _ = http_json(_env("CODEX_USAGE_URL", DEFAULT_USAGE_URL), headers=headers, timeout=25.0, retries=2)
    return status, data


def read_usage(auth: dict, auth_path: Path | None) -> dict:
    token = ensure_token(auth, auth_path)
    status, data = fetch_usage(token)
    if status in (401, 403) and _flag("CODEX_REFRESH", default=True):
        log(f"额度接口返回 HTTP {status}，令牌可能被提前吊销，尝试刷新后重试")
        auth = refresh_tokens(auth, auth_path)
        status, data = fetch_usage(auth["tokens"]["access_token"])
    if status != 200:
        detail = json.dumps(data, ensure_ascii=False)[:300]
        fail(2, f"额度接口返回 HTTP {status} {detail}")
    rate_limit = data.get("rate_limit")
    if not isinstance(rate_limit, dict):
        fail(2, f"额度接口没有返回 rate_limit：{json.dumps(data, ensure_ascii=False)[:300]}")
    if not rate_limit.get("primary_window") and not rate_limit.get("secondary_window"):
        fail(2, "额度接口没有返回五小时/七天窗口数据")
    return data


# ---------------------------------------------------------------- 组装数据

def _window(raw: object, fallback_seconds: int) -> dict | None:
    if not isinstance(raw, dict):
        return None
    percent = raw.get("used_percent")
    if not isinstance(percent, (int, float)):
        return None
    window_seconds = raw.get("limit_window_seconds")
    reset_at = raw.get("reset_at")
    reset_after = raw.get("reset_after_seconds")
    return {
        "used": max(0.0, min(100.0, float(percent))),
        "window_seconds": int(window_seconds) if isinstance(window_seconds, (int, float)) else fallback_seconds,
        "reset_at": int(reset_at) if isinstance(reset_at, (int, float)) else 0,
        "reset_after": int(reset_after) if isinstance(reset_after, (int, float)) else 0,
    }


def _percent(value: float) -> str:
    return f"{value:.0f}%" if abs(value - round(value)) < 0.05 else f"{value:.1f}%"


def _duration(seconds: int) -> str:
    seconds = max(0, int(seconds))
    if seconds < 60:
        return f"{seconds} 秒"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes} 分钟"
    hours, minutes = divmod(minutes, 60)
    if hours < 24:
        return f"{hours} 小时 {minutes} 分" if minutes else f"{hours} 小时"
    days, hours = divmod(hours, 24)
    return f"{days} 天 {hours} 小时" if hours else f"{days} 天"


def _clock(epoch: int) -> str:
    return datetime.fromtimestamp(epoch).strftime("%m-%d %H:%M") if epoch > 0 else "未知"


def _tone(remaining: float, warn: int, critical: int) -> str:
    if remaining <= 100 - critical:
        return "critical"
    if remaining <= 100 - warn:
        return "warning"
    return "normal"


def _progress_block(block_id: str, label: str, remaining: float, warn: int, critical: int) -> dict:
    """额度按“剩余”展示：剩余越少风险越高，所以 bands 随数值升高而从 critical 回到 normal。"""
    # bands 的 until 必须严格递增、大于 min 且末段等于 max；边界为 0 或与 max 重合时省略该段。
    edges = {100 - critical, 100 - warn}
    bands = [
        {"until": until, "tone": _tone(until, warn, critical)}
        for until in sorted(edge for edge in edges if 0 < edge < 100)
    ]
    bands.append({"until": 100, "tone": "normal"})
    return {
        "kind": "progress",
        "id": block_id,
        "label": label,
        "value": round(remaining, 1),
        "max": 100,
        "unit": "%",
        "tone": _tone(remaining, warn, critical),
        "bands": bands,
    }


def build_state(data: dict) -> tuple[str, str, list, str]:
    """返回 (标题, 副标题, 数据块, 摘要正文)。"""
    rate_limit = data.get("rate_limit") or {}
    session = _window(rate_limit.get("primary_window"), 18000)
    weekly = _window(rate_limit.get("secondary_window"), 604800)
    warn = _int_env("WARN_PERCENT", 70, 1, 99)
    critical = _int_env("CRITICAL_PERCENT", 90, warn, 100)

    plan = str(data.get("plan_type") or "").strip()
    subtitle = f"套餐 {plan}" if plan else ""

    blocks: list[dict] = []
    summary: list[str] = []
    if session:
        left = 100.0 - session["used"]
        blocks.append(_progress_block("session", "5 小时额度剩余", left, warn, critical))
        summary.append(f"5 小时窗口剩余 {_percent(left)}")
    if weekly:
        left = 100.0 - weekly["used"]
        blocks.append(_progress_block("week", "周额度剩余", left, warn, critical))
        summary.append(f"七天窗口剩余 {_percent(left)}")

    detail_items: list[dict] = []
    resets: list[str] = []
    if plan:
        detail_items.append({"id": "plan", "label": "套餐", "text": plan})
    if session:
        if session["reset_at"] > 0:
            detail_items.append({
                "id": "session-reset",
                "label": "5 小时重置",
                "text": f"{_duration(session['reset_after'])}后 · {_clock(session['reset_at'])}",
            })
            blocks.append({"kind": "countdown", "id": "session-countdown", "label": "5 小时重置倒计时", "target_at_ms": session["reset_at"] * 1000})
            resets.append(f"5 小时窗口 {_duration(session['reset_after'])}后重置")
    if weekly:
        if weekly["reset_at"] > 0:
            detail_items.append({
                "id": "week-reset",
                "label": "周额度重置",
                "text": f"{_duration(weekly['reset_after'])}后 · {_clock(weekly['reset_at'])}",
            })
            blocks.append({"kind": "countdown", "id": "week-countdown", "label": "周额度重置倒计时", "target_at_ms": weekly["reset_at"] * 1000})
            resets.append(f"周额度 {_duration(weekly['reset_after'])}后重置")

    credits = data.get("credits") or {}
    if isinstance(credits, dict) and credits:
        if credits.get("unlimited"):
            credit_text = "无限"
        elif credits.get("has_credits"):
            credit_text = f"可用，余额 {credits.get('balance', '未知')}"
        else:
            credit_text = "无"
        detail_items.append({"id": "credits", "label": "额外额度", "text": credit_text})
    reset_credits = data.get("rate_limit_reset_credits") or {}
    if isinstance(reset_credits, dict) and isinstance(reset_credits.get("available_count"), (int, float)):
        detail_items.append({"id": "reset-credits", "label": "重置次数", "text": f"{int(reset_credits['available_count'])} 次"})
    if detail_items:
        blocks.append({"kind": "list", "id": "detail", "label": "明细", "items": detail_items[:12]})

    if rate_limit.get("limit_reached"):
        blocks.append({"kind": "alert", "id": "limit", "text": "额度已用尽，等待窗口重置", "tone": "critical"})
        summary.append("额度已用尽")

    body = "，".join(summary) if summary else "暂无额度数据"
    if resets:
        body += "。" + "，".join(resets)
    return TITLE, subtitle, blocks[:16], body + "。"


# ---------------------------------------------------------------- 版本与上报

def topic_revision(base: str, key: str, topic: str) -> tuple[int, bool]:
    """返回 (服务端当前版本, 主题是否已存在)。"""
    url = f"{base}/api/v1/states/meta?topic_key={parse.quote(topic, safe='')}"
    status, data, _ = http_json(url, headers={"Authorization": f"Bearer {key}"}, timeout=20.0, retries=2)
    if status == 404:
        return 0, False
    if status != 200:
        fail(3, f"查询主题版本失败：HTTP {status} {json.dumps(data, ensure_ascii=False)[:300]}")
    revision = data.get("revision")
    return (int(revision) if isinstance(revision, (int, float)) else 0), True


def main() -> int:
    base = _env("BEACON_BASE_URL")
    key = _env("BEACON_PUSH_KEY")
    dry_run = _flag("DRY_RUN")
    if not base:
        fail(1, "缺少环境变量 BEACON_BASE_URL（例如 https://push.gzautobot.top）")
    if not base.startswith(("https://", "http://")):
        fail(1, "BEACON_BASE_URL 必须以 https:// 或 http:// 开头")
    if not key and not dry_run:
        fail(1, "缺少环境变量 BEACON_PUSH_KEY（App 里复制的推送密钥或应用令牌）")
    base = base.rstrip("/")
    topic = _env("TOPIC_KEY", "widget/codex-quota")
    with RunLock(_lock_path()):
        return _run(base, key, topic, dry_run)


def _run(base: str, key: str, topic: str, dry_run: bool) -> int:
    auth, auth_path = load_auth()
    data = read_usage(auth, auth_path)
    title, subtitle, blocks, body = build_state(data)
    log(body)

    state_path = _state_path()
    journal = _read_json(state_path) or {}
    observed_at_ms = int(time.time() * 1000)
    fresh = _int_env("FRESH_FOR_SECONDS", 3600, 0, 604800)
    payload = {
        "mode": "state",
        "schema_version": 1,
        "topic_key": topic,
        "title": title,
        "subtitle": subtitle,
        "body": body,
        "blocks": blocks,
        "observed_at_ms": observed_at_ms,
        "ttl": _int_env("BEACON_TTL", 604800, 0, 604800),
        "group": _env("BEACON_GROUP", "codex"),
        "priority": _int_env("BEACON_PRIORITY", 0, 0, 10),
    }
    if fresh:
        # 新鲜度不能超过有效 TTL（ttl=0 时服务端按默认七天算），否则服务端会拒绝。
        payload["fresh_for_seconds"] = min(fresh, payload["ttl"] or 604800)

    if dry_run:
        log("DRY_RUN 模式，不实际上报。请求内容如下：")
        print(json.dumps(dict(payload, revision="<查询后分配>"), ensure_ascii=False, indent=2), flush=True)
        return 0

    # 上一次异常退出留下的请求先补发，避免版本号被跳过。
    if isinstance(journal.get("pending"), dict):
        log("补发上次未确认的请求")
        try:
            http_json(f"{base}/api/v1/push", method="POST", headers={"Authorization": f"Bearer {key}"}, payload=journal["pending"], timeout=25.0, retries=2)
        except Exception as exc:  # noqa: BLE001 - 补发失败不阻塞本次采集
            log(f"补发失败，忽略：{exc}")

    server_revision, exists = topic_revision(base, key, topic)
    if not exists:
        fail(1, f"数据主题 {topic} 还没创建：请在 App「服务器管理 → 数据主题」新建，并把标识填成 {topic}（或用 TOPIC_KEY 改成已创建的主题）")
    revision = max(int(journal.get("revision") or 0), server_revision) + 1
    payload["revision"] = revision

    journal = {"revision": revision, "pending": payload}
    try:
        _write_json(state_path, journal)
    except OSError as exc:
        log(f"写入版本日志失败（不影响上报）：{exc}")

    status, result, _ = http_json(f"{base}/api/v1/push", method="POST", headers={"Authorization": f"Bearer {key}"}, payload=payload, timeout=25.0, retries=2)

    if status == 409:
        # 版本冲突：通常是对服务端版本判断落后（同一主题有另一个写入方），重查一次再提交。
        log("服务端版本冲突，重新查询后重试一次")
        server_revision, exists = topic_revision(base, key, topic)
        if not exists:
            fail(1, f"数据主题 {topic} 在上报过程中被删除了，请重新创建")
        revision = max(revision, server_revision) + 1
        payload["revision"] = revision
        try:
            _write_json(state_path, {"revision": revision, "pending": payload})
        except OSError as exc:
            log(f"写入版本日志失败（不影响上报）：{exc}")
        status, result, _ = http_json(f"{base}/api/v1/push", method="POST", headers={"Authorization": f"Bearer {key}"}, payload=payload, timeout=25.0, retries=2)

    if status == 200:
        log(f"上报成功：revision {result.get('revision', revision)}，{len(blocks)} 个数据块")
        _record(state_path, revision)
        return 0

    detail = json.dumps(result, ensure_ascii=False)[:400] if result else ""
    hints = {
        400: "上报内容不合法（主题标识、块数量或字段超限）",
        401: "推送密钥无效或已被撤销，请在 App 里重新复制",
        404: "数据主题不存在，请先在 App「服务器管理 → 数据主题」创建",
        429: "上报频率超限，稍后重试",
        507: "服务端存储已满",
    }
    hint = hints.get(status, "")
    fail(3, f"上报失败：HTTP {status} {detail}{('；' + hint) if hint else ''}")
    return 3


def _record(path: Path, revision: int) -> None:
    try:
        _write_json(path, {"revision": revision})
    except OSError as exc:
        log(f"写入版本日志失败（不影响上报）：{exc}")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
