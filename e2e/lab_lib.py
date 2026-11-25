#!/usr/bin/env python3
"""doris-reactive-lab e2e 公共库：HTTP 驱动场景端点 + 断言 + 证据收集。
仅用标准库（urllib/json），无三方依赖。断言失败不中断后续用例（全量跑完再汇总）。"""
import json
import os
import sys
import time
import urllib.request
import urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "http://localhost:" + os.environ.get("LAB_HTTP_PORT", "8081")


def load_local_env():
    """解析项目根 local.env（凭据文件，gitignored）→ 环境变量形式 dict"""
    env = {}
    path = os.path.join(ROOT, "local.env")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                env[k.strip()] = v.strip()
    return env


def _req(method, path, timeout, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw
    except Exception as e:  # 连接失败等
        return None, str(e)


def get(path, timeout=120):
    return _req("GET", path, timeout)


def post(path, timeout=120, body=None):
    return _req("POST", path, timeout, body)


class Ctx:
    """用例上下文：证据收集 + 断言。所有断言结果进入 self.results"""

    def __init__(self):
        self.results = []          # {id, status, detail}
        self.raw = []              # 场景端点原始返回（报告附录）

    def log(self, cid, status, detail=""):
        detail = str(detail if detail is not None else "")
        self.results.append({"id": cid, "status": status, "detail": detail})
        print(f"LAB|{cid}|e2e|{status}|{detail}".replace("\n", " "), flush=True)

    def ok(self, cid, detail=""):
        self.log(cid, "PASS", detail)

    def fail(self, cid, detail=""):
        self.log(cid, "FAIL", detail)

    def check(self, cid, cond, detail_ok="", detail_fail=""):
        if cond:
            self.ok(cid, detail_ok)
        else:
            self.fail(cid, detail_fail)
        return cond

    def fetch_group(self, group):
        st, body = get(f"/scenario/{group}", timeout=180)
        if st != 200 or not isinstance(body, list):
            self.fail(f"{group.upper()}-endpoint", f"HTTP {st}: {str(body)[:300]}")
            return []
        self.raw.append({"group": group, "steps": body})
        return body

    def find(self, steps, sid):
        for s in steps:
            if s.get("id") == sid:
                return s
        return None

    def assert_step(self, steps, sid, cond_fn, detail_ok="", detail_fail=""):
        s = self.find(steps, sid)
        if s is None:
            self.fail(sid, "场景端点未返回该步骤")
            return None
        cond, why = cond_fn(s)
        detail = detail_ok if cond else (detail_fail or why or json.dumps(s.get("data", {}), ensure_ascii=False)[:400])
        self.check(sid, cond, detail, detail)
        return s


def wait_healthy(max_wait=90):
    t0 = time.time()
    while time.time() - t0 < max_wait:
        st, body = get("/q/health/ready", timeout=5)
        if st in (200, 503):
            return st, body
        time.sleep(2)
    return None, None


def render_report(ctx, extra_sections=None):
    """写 e2e/report/report.md"""
    rep_dir = os.path.join(ROOT, "e2e", "report")
    os.makedirs(rep_dir, exist_ok=True)
    total = len(ctx.results)
    passed = sum(1 for r in ctx.results if r["status"] == "PASS")
    failed = sum(1 for r in ctx.results if r["status"] == "FAIL")
    lines = [
        "# doris-reactive-lab e2e 报告",
        "",
        f"- 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 用例：{total}，PASS {passed}，FAIL {failed}",
        "",
        "| 用例 | 结果 | 说明 |",
        "|---|---|---|",
    ]
    for r in ctx.results:
        d = str(r.get("detail") or "")
        lines.append(f"| {r['id']} | {r['status']} | {d[:200]} |")
    lines += ["", "## 场景端点原始返回（附录）", ""]
    for raw in ctx.raw:
        lines.append(f"### group {raw['group']}")
        lines.append("```json")
        lines.append(json.dumps(raw["steps"], ensure_ascii=False, indent=1)[:6000])
        lines.append("```")
    if extra_sections:
        lines += extra_sections
    path = os.path.join(rep_dir, "report.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return passed, failed, total
