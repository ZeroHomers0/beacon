#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# cron: */30 * * * *
# */30 * * * * hot_search_to_beacon.py
# new Env('Beacon 热搜榜');
"""60s 热搜榜 -> Beacon 数据主题（青龙面板定时任务）

用 60s API 的榜单接口抓热搜，写进 Beacon「数据主题」，手机桌面小组件用「文字列表」
样式展示。每个榜单一个主题，互不影响；主题必须先在 App 里预创建，脚本不会自动创建。

用法（青龙面板）：
    1. 把本文件放到青龙的脚本目录，例如 /ql/data/scripts/hot-search/hot_search_to_beacon.py
    2. 在 App「服务器管理 → 数据主题」创建对应主题（默认 hot/weibo、hot/baidu、hot/zhihu）
    3. 在「环境变量」里添加 BEACON_BASE_URL 和 BEACON_PUSH_KEY
    4. 定时任务命令填 `task hot-search/hot_search_to_beacon.py`，定时规则填 `*/30 * * * *`
    5. 长按桌面添加 Beacon 小组件，绑定主题；样式选「自动」时会按文字列表渲染

本脚本只依赖 Python 标准库（urllib），青龙镜像无需安装任何依赖。

环境变量：
    BEACON_BASE_URL   必填，Beacon 服务地址，例如 https://push.gzautobot.top
    BEACON_PUSH_KEY   必填，推送密钥（App -> 服务器管理 -> 连接设置 -> 复制推送密钥）
    HOT_SOURCES       可选，逗号分隔的榜单，默认 weibo,baidu,zhihu；all 表示全部
    HOT_LIMIT         可选，每个榜单取前 N 条，1-12，默认 10
    HOT_TOPIC_PREFIX  可选，主题前缀，默认 hot（榜单 weibo 对应主题 hot/weibo）
    HOT_SHOW_HEAT     可选，条目末尾附热度，默认开启；设 0 关闭
    HOT_PRIORITY      可选，优先级 0-10，默认 1（只刷新桌面，不弹通知）
    HOT_GROUP         可选，消息分组，默认 hot
    HOT_TTL           可选，有效期（秒），默认 86400
    HOT_FRESH_FOR     可选，新鲜期（秒），超过后桌面显示「数据已过期」，默认 3600
    SIXTY_API_BASE    可选，60s API 地址，默认 https://60s.gzautobot.top
    HOT_STATE_FILE    可选，版本水位文件，默认脚本同目录 .hot_search_to_beacon_state.json
    DRY_RUN           可选，只打印将要发送的内容，不真正推送，1/true 开启

退出码：0 全部成功，1 配置错误，2 没有任何榜单拉到数据，3 有主题推送失败
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import NoReturn
from urllib import error, parse, request

SCRIPT_DIR = Path(__file__).resolve().parent
CN_TZ = timezone(timedelta(hours=8))
USER_AGENT = "beacon-qinglong-hot/1.0 (+https://github.com/ZeroHomers0/60s)"

# 榜单来源：标识 -> (接口路径, 主题显示名)。新增来源时同步 README 的表格。
SOURCES = {
    "weibo": ("/v2/weibo", "微博热搜"),
    "baidu": ("/v2/baidu/hot", "百度热搜"),
    "zhihu": ("/v2/zhihu", "知乎热榜"),
    "douyin": ("/v2/douyin", "抖音热榜"),
    "toutiao": ("/v2/toutiao", "头条热榜"),
    "rednote": ("/v2/rednote", "小红书热搜"),
    "tieba": ("/v2/baidu/tieba", "贴吧热议"),
    "bili": ("/v2/bili", "B站热搜"),
    "itnews": ("/v2/it-news/rank", "IT之家热榜"),
    "hackernews": ("/v2/hacker-news/top", "Hacker News"),
}
DEFAULT_SOURCES = "weibo,baidu,zhihu"


class BoardError(Exception):
    """单个榜单拉取失败；不影响其他榜单继续推送。"""


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
    print(f"[hot {stamp}] {message}", flush=True)


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
                return resp.status, _decode(resp.read()), dict(resp.headers.items())
        except error.HTTPError as exc:
            data = _decode(exc.read())
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


# ---------------------------------------------------------------- 60s 榜单

def _short_number(value: float) -> str:
    if value >= 1_0000_0000:
        return f"{value / 1_0000_0000:.1f}亿"
    if value >= 1_0000:
        return f"{value / 1_0000:.1f}万"
    return str(int(value))


def item_heat(item: dict) -> str:
    """不同榜单的热度字段不统一：优先用接口给好的描述，其次才是原始数值。"""
    for key in ("hot_value_desc", "score_desc"):
        text = str(item.get(key) or "").strip()
        if text:
            return text
    for key in ("hot_value", "score"):
        raw = item.get(key)
        if isinstance(raw, bool) or raw is None:
            continue
        if isinstance(raw, (int, float)):
            if raw > 0:
                return _short_number(float(raw))
            continue
        text = str(raw).strip()
        if not text:
            continue
        if text.isdigit():
            return _short_number(float(text))
        # 小红书等直接把 "947.5w" 这种带单位的字符串放在 score 里。
        if text[-1].lower() in {"w", "万", "亿"}:
            return text
    return ""


def fetch_board(api_base: str, source: str, limit: int, show_heat: bool) -> list[dict]:
    path, _ = SOURCES[source]
    url = f"{api_base.rstrip('/')}{path}"
    try:
        status, data, _ = http_json(url, timeout=20.0, retries=2)
    except Exception as exc:  # noqa: BLE001 - 网络异常统一算这个榜单失败
        raise BoardError(f"请求 {url} 出错：{exc}") from exc
    if status != 200 or data.get("code") != 200:
        raise BoardError(f"{url} 返回异常：HTTP {status} {json.dumps(data, ensure_ascii=False)[:200]}")
    rows = data.get("data")
    if not isinstance(rows, list) or not rows:
        raise BoardError(f"{url} 没有返回榜单内容")

    items: list[dict] = []
    seen: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        title = str(row.get("title") or "").strip()
        if not title or title in seen:
            continue
        seen.add(title)
        heat = item_heat(row) if show_heat else ""
        text = f"{title} · {heat}" if heat else title
        items.append({"id": f"{source}-{len(items) + 1}", "text": text[:300]})
        if len(items) >= limit:
            break
    if not items:
        raise BoardError(f"{url} 没有可用的榜单标题")
    return items


# ---------------------------------------------------------------- Beacon 状态

def _auth(key: str) -> dict:
    return {"Authorization": f"Bearer {key}"}


def meta_revision(base: str, key: str, topic: str) -> int:
    url = f"{base}/api/v1/states/meta?topic_key={parse.quote(topic, safe='')}"
    try:
        status, data, _ = http_json(url, headers=_auth(key), timeout=20.0, retries=2)
    except Exception as exc:  # noqa: BLE001
        raise BoardError(f"读取 {topic} 版本出错：{exc}") from exc
    if status == 404:
        raise BoardError(
            f"主题 {topic} 未登记：先在 App「服务器管理 → 数据主题」创建该标识，"
            "推送密钥不能创建主题"
        )
    if status != 200:
        raise BoardError(f"读取 {topic} 版本失败：HTTP {status} {json.dumps(data, ensure_ascii=False)[:200]}")
    return int(data.get("revision") or 0)


def build_payload(topic: str, source: str, items: list[dict], revision: int) -> dict:
    name = SOURCES[source][1]
    ttl = _int_env("HOT_TTL", 86400, 60, 604800)
    fresh = _int_env("HOT_FRESH_FOR", 3600, 0, ttl)
    stamp = datetime.now(CN_TZ).strftime("%m-%d %H:%M")
    lines = [f"{index}. {item['text']}" for index, item in enumerate(items, start=1)]
    return {
        "mode": "state",
        "schema_version": 1,
        "topic_key": topic,
        "revision": revision,
        "observed_at_ms": int(time.time() * 1000),
        "fresh_for_seconds": fresh,
        "ttl": ttl,
        "title": name,
        # 桌面「文字列表」会自己加序号，所以 items[].text 不带编号；正文留一份完整列表做降级。
        "subtitle": f"Top {len(items)} · {stamp} 更新",
        "body": "\n".join(lines),
        "priority": _int_env("HOT_PRIORITY", 1, 0, 10),
        "group": _env("HOT_GROUP", "hot"),
        "blocks": [{"kind": "list", "id": "hot", "label": name, "items": items}],
    }


def push_state(base: str, key: str, payload: dict) -> tuple[int, dict]:
    try:
        status, data, _ = http_json(
            f"{base}/api/v1/push",
            method="POST",
            headers=_auth(key),
            payload=payload,
            timeout=25.0,
            retries=2,
        )
    except Exception as exc:  # noqa: BLE001
        return 0, {"error": f"{type(exc).__name__}: {exc}"}
    return status, data


HINTS = {
    400: "内容不合法（榜单为空或超长），可减少 HOT_LIMIT",
    401: "推送密钥无效或已被撤销，请在 App 里重新复制",
    404: "主题未登记，先在 App 的「数据主题」里创建",
    409: "主题已删除或版本冲突，检查 App 里的主题状态",
    429: "推送频率超限，稍后重试",
    507: "服务端存储已满",
}


# ---------------------------------------------------------------- 版本水位

def state_path() -> Path:
    custom = _env("HOT_STATE_FILE")
    if custom:
        return Path(custom).expanduser()
    return SCRIPT_DIR / ".hot_search_to_beacon_state.json"


def load_journal(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def save_journal(path: Path, journal: dict) -> None:
    try:
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(journal, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(path)
    except OSError as exc:
        log(f"写出版本水位失败（不影响已完成的推送）：{exc}")


# ---------------------------------------------------------------- 主流程

def parse_sources() -> list[str]:
    raw = _env("HOT_SOURCES", DEFAULT_SOURCES)
    names = [part.strip().lower() for part in raw.replace("，", ",").split(",") if part.strip()]
    if not names or "all" in names:
        return list(SOURCES)
    unknown = [name for name in names if name not in SOURCES]
    if unknown:
        fail(1, f"HOT_SOURCES 里有不认识的榜单：{', '.join(unknown)}；可选：{', '.join(SOURCES)}")
    return names


def publish(base: str, key: str, topic: str, source: str, items: list[dict], journal: dict, path: Path) -> bool:
    for attempt in range(2):
        try:
            revision = max(int(journal.get(topic) or 0), meta_revision(base, key, topic)) + 1
        except BoardError as exc:
            log(str(exc))
            return False
        payload = build_payload(topic, source, items, revision)
        status, data = push_state(base, key, payload)
        if status == 200:
            accepted = int(data.get("revision") or revision)
            journal[topic] = accepted
            save_journal(path, journal)
            suffix = "（内容未变，服务端返回 unchanged）" if data.get("unchanged") else ""
            log(f"{topic} 已推送 {len(items)} 条，版本 {accepted}{suffix}")
            return True
        code = str(data.get("error") or data.get("message") or "")
        if status == 409 and code in {"stale_state_revision", "state_revision_conflict"} and attempt == 0:
            log(f"{topic} 版本冲突（{code}），重新读取水位后重试")
            continue
        detail = json.dumps(data, ensure_ascii=False)[:300] if data else ""
        hint = HINTS.get(status, "")
        log(f"{topic} 推送失败：HTTP {status} {code or detail}{('；' + hint) if hint else ''}")
        return False
    return False


def main() -> int:
    base = _env("BEACON_BASE_URL")
    key = _env("BEACON_PUSH_KEY")
    dry_run = _flag("DRY_RUN")
    if not base:
        fail(1, "缺少环境变量 BEACON_BASE_URL（例如 https://push.gzautobot.top）")
    if not base.startswith(("https://", "http://")):
        fail(1, "BEACON_BASE_URL 必须以 https:// 或 http:// 开头")
    if not key and not dry_run:
        fail(1, "缺少环境变量 BEACON_PUSH_KEY（App 里复制的推送密钥）")

    api_base = _env("SIXTY_API_BASE", "https://60s.gzautobot.top")
    prefix = _env("HOT_TOPIC_PREFIX", "hot").strip("/")
    limit = _int_env("HOT_LIMIT", 10, 1, 12)
    show_heat = _flag("HOT_SHOW_HEAT", default=True)
    sources = parse_sources()

    path = state_path()
    journal = load_journal(path)
    log(f"准备刷新 {len(sources)} 个榜单：{', '.join(sources)}；每个取前 {limit} 条")

    fetched = 0
    pushed = 0
    failed = 0
    for source in sources:
        topic = f"{prefix}/{source}"
        try:
            items = fetch_board(api_base, source, limit, show_heat)
        except BoardError as exc:
            log(f"{source} 跳过：{exc}")
            failed += 1
            continue
        fetched += 1
        if dry_run:
            payload = build_payload(topic, source, items, int(journal.get(topic) or 0) + 1)
            log(f"DRY_RUN 模式，不实际推送；{topic} 的请求内容如下：")
            print(json.dumps(payload, ensure_ascii=False, indent=2), flush=True)
            continue
        if publish(base, key, topic, source, items, journal, path):
            pushed += 1
        else:
            failed += 1

    if dry_run:
        return 0 if fetched else 2
    if not fetched:
        fail(2, f"{len(sources)} 个榜单都没有拉到数据，检查 SIXTY_API_BASE 或稍后重试")
    if pushed == 0:
        fail(3, "所有榜单都推送失败，检查 BEACON_BASE_URL、推送密钥和主题是否已创建")
    if failed:
        log(f"本轮完成：{pushed} 个榜单已更新，{failed} 个失败；失败原因见上面的日志")
        return 3
    log(f"本轮完成：{pushed} 个榜单已更新")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
