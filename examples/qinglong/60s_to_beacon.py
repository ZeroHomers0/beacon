#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# cron: 30 7 * * *
# 30 7 * * * 60s_to_beacon.py
# new Env('Beacon 每日新闻');
"""60s 新闻 -> Beacon 推送（青龙面板定时任务）

用法（青龙面板）：
    1. 把本文件放到青龙的脚本目录，例如 /ql/data/scripts/60s_to_beacon.py
    2. 在「环境变量」里添加 BEACON_BASE_URL 和 BEACON_PUSH_KEY
    3. 定时任务命令填 `task 60s_to_beacon.py`，定时规则填 `30 7 * * *`

本脚本只依赖 Python 标准库（urllib），青龙镜像无需安装任何依赖。

环境变量：
    BEACON_BASE_URL    必填，Beacon 服务地址，例如 https://push.gzautobot.top
    BEACON_PUSH_KEY    必填，设备推送密钥（App -> 服务器管理 -> 连接设置 -> 复制推送密钥）
    SIXTY_API_BASE     可选，60s API 地址，默认 https://60s.gzautobot.top
    BEACON_GROUP       可选，消息分组，默认 60s
    BEACON_PRIORITY    可选，优先级 0-10，默认 5（普通通知）
    BEACON_TTL         可选，投递有效期（秒），默认 86400；0 表示服务端默认七天
    NEWS_LIMIT         可选，只推送前 N 条，0 表示全部，默认 0
    INCLUDE_IMAGE      可选，正文附带今日图片，1/true 开启，默认关闭
    INCLUDE_BUTTON     可选，附带「查看原文」按钮，1/true 开启，默认开启
    FORCE_PUSH         可选，忽略本地去重，1/true 开启，默认关闭
    DRY_RUN            可选，只打印将要发送的内容，不真正推送，1/true 开启
    STATE_FILE         可选，去重状态文件路径，默认脚本同目录

退出码：0 成功/已跳过，1 配置错误，2 拉取失败，3 推送失败
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import NoReturn
from urllib import error, request

SCRIPT_DIR = Path(__file__).resolve().parent
CN_TZ = timezone(timedelta(hours=8))
USER_AGENT = "beacon-qinglong-60s/1.0 (+https://github.com/ZeroHomers0/60s)"


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
    stamp = datetime.now(CN_TZ).strftime("%H:%M:%S")
    print(f"[60s {stamp}] {message}", flush=True)


def fail(code: int, message: str) -> NoReturn:
    log(f"失败：{message}")
    raise SystemExit(code)


def http_json(
    url: str,
    *,
    method: str = "GET",
    headers: dict | None = None,
    payload: dict | None = None,
    timeout: float = 20.0,
    retries: int = 2,
) -> tuple[int, dict, dict]:
    """返回 (状态码, JSON 响应体, 响应头)；网络错误与 5xx 会自动重试。"""
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_error = ""
    next_delay = 0
    for attempt in range(retries + 1):
        if attempt:
            delay = next_delay or min(2 ** attempt, 8)
            next_delay = 0
            log(f"第 {attempt + 1} 次重试，{delay}s 后继续：{last_error}")
            time.sleep(delay)
        req = request.Request(url, data=body, method=method)
        req.add_header("User-Agent", USER_AGENT)
        req.add_header("Accept", "application/json")
        if body is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                return resp.status, _decode(raw), dict(resp.headers.items())
        except error.HTTPError as exc:
            raw = exc.read()
            data = _decode(raw)
            # 4xx 是请求本身的问题，重试没有意义；429 例外，按 Retry-After 等待。
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
            raise
    raise RuntimeError(last_error or "unknown error")


def _decode(raw: bytes) -> dict:
    if not raw:
        return {}
    text = raw.decode("utf-8", errors="replace").strip()
    try:
        value = json.loads(text)
    except ValueError:
        return {"_raw": text[:2000]}
    if isinstance(value, dict):
        return value
    return {"_raw": value}


def _retry_after(value: str | None, default: int = 10) -> int:
    if not value:
        return default
    try:
        return max(1, min(int(value), 60))
    except ValueError:
        return default


# ---------------------------------------------------------------- 60s 数据

def fetch_news(api_base: str) -> dict:
    url = f"{api_base.rstrip('/')}/v2/60s"
    try:
        status, data, _ = http_json(url, timeout=20.0, retries=2)
    except Exception as exc:  # noqa: BLE001 - 网络异常统一转成退出码 2
        fail(2, f"请求 {url} 出错：{exc}")
    if status != 200 or data.get("code") != 200:
        fail(2, f"接口返回异常：HTTP {status} {json.dumps(data, ensure_ascii=False)[:300]}")
    payload = data.get("data") or {}
    news = payload.get("news") or []
    if not isinstance(news, list) or not news:
        fail(2, "接口没有返回新闻内容")
    return payload


def build_message(payload: dict, limit: int) -> tuple[str, str, str]:
    date = str(payload.get("date") or datetime.now(CN_TZ).strftime("%Y-%m-%d"))
    week = str(payload.get("day_of_week") or "")
    lunar = str(payload.get("lunar_date") or "")
    tip = str(payload.get("tip") or "").strip()
    news = [str(item).strip() for item in payload.get("news") or [] if str(item).strip()]
    if limit > 0:
        news = news[:limit]
    if not news:
        fail(2, "过滤后没有可推送的新闻")

    title = f"每天60秒读懂世界 · {date[5:] if len(date) >= 10 else date}"
    subtitle_bits = [part for part in (week, lunar) if part]
    subtitle = " · ".join(subtitle_bits) if subtitle_bits else f"共 {len(news)} 条"

    lines = [f"**{date}{(' ' + week) if week else ''}{(' · ' + lunar) if lunar else ''}**", ""]
    for index, item in enumerate(news, start=1):
        lines.append(f"{index}. {item}")
    if tip:
        lines.extend(["", f"> 微语：{tip}"])
    if _flag("INCLUDE_IMAGE") and payload.get("image"):
        image = str(payload["image"])
        if image.startswith("https://"):
            lines.extend(["", f"![今日60s]({image})"])
    return title, subtitle, "\n".join(lines)


def build_payload(title: str, subtitle: str, body: str, payload: dict) -> dict:
    message = {
        "title": title,
        "subtitle": subtitle,
        "body": body,
        "group": _env("BEACON_GROUP", "60s"),
        "priority": _int_env("BEACON_PRIORITY", 5, 0, 10),
        "ttl": _int_env("BEACON_TTL", 86400, 0, 604800),
    }
    if _flag("INCLUDE_BUTTON", default=True):
        link = str(payload.get("link") or "").strip()
        if link.startswith("https://") and len(link) <= 2048:
            message["actions"] = [{"title": "看原文", "url": link}]
    return message


# ---------------------------------------------------------------- 本地去重

def state_path() -> Path:
    custom = _env("STATE_FILE")
    if custom:
        return Path(custom).expanduser()
    return SCRIPT_DIR / ".60s_to_beacon_state.json"


def already_pushed(date: str, path: Path) -> dict | None:
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if isinstance(state, dict) and state.get("date") == date:
        return state
    return None


def remember(date: str, path: Path, message_id: str) -> None:
    record = {
        "date": date,
        "message_id": message_id,
        "pushed_at": datetime.now(CN_TZ).isoformat(timespec="seconds"),
    }
    try:
        path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as exc:
        log(f"写入去重状态失败（不影响推送结果）：{exc}")


# ---------------------------------------------------------------- 主流程

def main() -> int:
    push_base = _env("BEACON_BASE_URL")
    push_key = _env("BEACON_PUSH_KEY")
    dry_run = _flag("DRY_RUN")
    if not push_base:
        fail(1, "缺少环境变量 BEACON_BASE_URL（例如 https://push.gzautobot.top）")
    if not push_base.startswith(("https://", "http://")):
        fail(1, "BEACON_BASE_URL 必须以 https:// 或 http:// 开头")
    if not push_key and not dry_run:
        fail(1, "缺少环境变量 BEACON_PUSH_KEY（App 里复制的推送密钥）")

    payload = fetch_news(_env("SIXTY_API_BASE", "https://60s.gzautobot.top"))
    date = str(payload.get("date") or "")
    if not date:
        date = datetime.now(CN_TZ).strftime("%Y-%m-%d")
    news_count = len(payload.get("news") or [])

    path = state_path()
    previous = None if _flag("FORCE_PUSH") else already_pushed(date, path)
    if previous:
        log(f"{date} 的 {news_count} 条新闻已推送过（{previous.get('pushed_at')}），本次跳过；需要重推请设置 FORCE_PUSH=1")
        return 0

    title, subtitle, body = build_message(payload, _int_env("NEWS_LIMIT", 0, 0, 100))
    message = build_payload(title, subtitle, body, payload)

    if dry_run:
        log("DRY_RUN 模式，不实际推送。请求内容如下：")
        print(json.dumps(message, ensure_ascii=False, indent=2), flush=True)
        return 0

    url = f"{push_base.rstrip('/')}/api/v1/push"
    headers = {
        "Authorization": f"Bearer {push_key}",
        "Idempotency-Key": f"60s-{date}",
    }
    try:
        status, data, _ = http_json(url, method="POST", headers=headers, payload=message, timeout=25.0, retries=2)
    except Exception as exc:  # noqa: BLE001
        fail(3, f"推送请求出错：{exc}")

    if status == 200:
        message_id = str(data.get("id") or "")
        log(f"推送成功：{date} 共 {news_count} 条，消息 ID {message_id or '(未知)'}")
        remember(date, path, message_id)
        return 0

    if status == 409:
        # 同一 Idempotency-Key 在一周内重复投递：服务端已保存过相同内容。
        log(f"服务端判定为重复推送（409 {data.get('error') or data.get('message') or ''}），视为已完成")
        remember(date, path, str(data.get("id") or ""))
        return 0

    detail = json.dumps(data, ensure_ascii=False)[:500] if data else ""
    hints = {
        401: "推送密钥无效或已被撤销，请在 App 里重新复制",
        400: "消息内容不合法（正文为空或超长）",
        413: "请求体过大，可减少 NEWS_LIMIT",
        429: "推送频率超限，稍后重试",
        507: "服务端存储已满",
    }
    hint = hints.get(status, "")
    fail(3, f"推送失败：HTTP {status} {detail}{('；' + hint) if hint else ''}")
    return 3


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
