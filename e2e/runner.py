#!/usr/bin/env python3
"""doris-reactive-lab e2e runner：场景执行引擎(HTTP)之上的用例层。
用法：python3 e2e/runner.py [组名...]（默认 a,health,b,c,d,e,f,g）
线程架构（矩阵结论的镜像）：pool/jdbc 腿在 @Blocking worker（/scenario/{b,c,e}），
HR 腿在 eventloop（/scenario/{b,c,e}-hr），runner 合并两份数据后断言。"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lab_lib as lab  # noqa: E402

from lab_lib import Ctx  # noqa: E402


def run_health(ctx):
    """A6/A7：主 boot 的 health check 集成行为（A8/A8b/A9/A9b 属 boot 变体，run-e2e.sh 负责）"""
    st, body = lab.get("/q/health/ready", timeout=15)
    if st != 200 or not isinstance(body, dict):
        ctx.fail("A6", f"readiness HTTP {st}: {str(body)[:200]}")
    else:
        checks = body.get("checks", [])
        names = [c.get("name", "") for c in checks]
        downs = [c for c in checks if c.get("status") != "UP"]
        ctx.raw.append({"group": "health-ready", "steps": checks})
        ctx.check("A6", body.get("status") == "UP" and not downs,
                  f"readiness=UP checks={names}",
                  f"readiness={body.get('status')} 非UP项={[(c.get('name'), c.get('status')) for c in downs]}")
        rep_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report")
        os.makedirs(rep_dir, exist_ok=True)
        with open(os.path.join(rep_dir, "health-baseline.json"), "w", encoding="utf-8") as f:
            json.dump(checks, f, ensure_ascii=False, indent=1)

    st, body = lab.get("/q/health/live", timeout=15)
    if st != 200 or not isinstance(body, dict):
        ctx.fail("A7", f"liveness HTTP {st}")
        return
    names = [c.get("name", "") for c in body.get("checks", [])]
    has_ds = any("datasource" in n.lower() or "connection" in n.lower() for n in names)
    ctx.check("A7", body.get("status") == "UP" and not has_ds,
              f"liveness=UP checks={names}",
              f"liveness={body.get('status')} 含数据源check? {has_ds} checks={names}")


def run_pool(ctx):
    steps = ctx.fetch_group("a")
    if not steps:
        return
    ctx.assert_step(steps, "A1", lambda s: (s["ok"] and 0 < s["data"].get("firstConnMs", 99999) < 5000,
                                            f"建池+首连+首查 {s['data'].get('firstConnMs')}ms"))
    s = ctx.find(steps, "A2")
    if s:
        d = s["data"]
        ctx.check("A2", s["ok"] and d.get("failures") == 0,
                  f"max-size={d.get('maxSize')} {d.get('total')}并发全成功 单查{d.get('singleMs')}ms 墙钟{d.get('wallMs')}ms（排队串行化）",
                  f"failures={d.get('failures')} data={d}")

    ctx.assert_step(steps, "A3", lambda s: (s["ok"] and 1500 <= s["data"].get("timeoutMs", 0) <= 8000,
                                            f"池耗尽=等 connectionTimeout(2s) 后失败（实测 {s['data'].get('timeoutMs')}ms；默认 60s 为挂等待非快速失败）"))

    s = ctx.find(steps, "A4")
    if s:
        d = s["data"]
        ctx.check("A4", s["ok"], f"KILL cid={d.get('victimId')} 后 {d.get('recovery')}；被杀连接报错={str(d.get('victimErr'))[:120]}",
                  f"自愈失败: {d.get('recovery')}")

    ctx.assert_step(steps, "A5", lambda s: (s["ok"], f"配置路径建池 OK cid={s['data'].get('connectionId')} user={s['data'].get('currentUser')}"))


def run_binding(ctx):
    steps = ctx.fetch_group("b")
    hr_steps = ctx.fetch_group("b-hr")

    def b1_cond(s):
        d = s["data"]
        rejected = (not d.get("poolOk")) and "mismatched input" in str(d.get("poolErr"))
        return rejected, f"LIMIT ?,? 被 FE prepare 拒 err={str(d.get('poolErr'))[:160]}"
    ctx.assert_step(steps, "B1", b1_cond)

    s = ctx.find(hr_steps, "B1-hr")
    if s:
        hr_err = str(s.get("error") or "")
        ctx.check("B1-hr", "mismatched input" in hr_err,
                  f"HR native LIMIT ?,? 同被 FE 拒 err={hr_err[:150]}",
                  f"hr 腿形态异常 err={hr_err[:200]} data={s.get('data')}")

    def b1c_cond(s):
        return s["data"].get("poolIds") == [2, 6, 7], f"字面量 LIMIT 3 OFFSET 1+参数化 WHERE ids={s['data'].get('poolIds')}"
    ctx.assert_step(steps, "B1c", b1c_cond)

    s = ctx.find(hr_steps, "B1c-hr")
    if s:
        ctx.check("B1c-hr", s["data"].get("ids") == [2, 6, 7],
                  f"HR native 字面量 LIMIT ids={s['data'].get('ids')}",
                  f"ids={s['data'].get('ids')} err={s.get('error')}")

    s = ctx.find(hr_steps, "B1d")
    if s:
        d = s["data"]
        if d.get("limitShape") == "parameterized-limit-rejected":
            ctx.log("B1d", "INFO", f"HR setMaxResults 生成参数化 limit ?,? 被 FE 拒（HQL/原生分页同根因）err={str(s.get('error'))[:160]}")
        else:
            ctx.check("B1d", d.get("ids") == [2, 3, 4], f"setMaxResults 内联 LIMIT 可用 ids={d.get('ids')}",
                      f"ids={d.get('ids')}")

    s = ctx.find(steps, "B2")
    if s:
        d = s["data"]
        expects = {
            "pool-String": "公司码测试", "pool-Integer": "42",
            "pool-Long>2^53": "9007199254740993", "pool-Double": "3.14159",
            "pool-DATETIME": "2024-06-01T08:00", "pool-NULL": "null", "pool-空串": "",
        }
        bad = [k for k, want in expects.items() if want not in str(d.get(k, "<missing>"))]
        dec = str(d.get("pool-DECIMAL", "<missing>"))
        if "123.456789" not in dec:
            bad.append("pool-DECIMAL")
        ctx.check("B2-pool", not bad,
                  f"pool 8 类型绑定全对（DECIMAL 回显类型={dec}）",
                  f"不匹配: {bad}；明细={json.dumps(d, ensure_ascii=False)[:500]}")

    s = ctx.find(hr_steps, "B2-hr")
    if s:
        d = s["data"]
        # HR native 参数回显：字符串回显为 ClobProxy（归一后为文本）；DATETIME 回显 LocalDateTime
        hr_bad = [k for k in d if str(d[k]).startswith("ERR")]
        ctx.check("B2-hr", not hr_bad,
                  f"HR 8 类型绑定全对（明细={json.dumps(d, ensure_ascii=False)[:350]}）",
                  f"异常项={hr_bad}")

    def b3_cond(s):
        d = s["data"]
        want_ids = [1, 3, 4, 5, 9, 10]  # 种子 login×4(1,3,5,10)+logout×2(4,9)
        got_ids = sorted(int(str(m).split(":")[0]) for m in d.get("matched", []))
        return d.get("count") == 6 and got_ids == want_ids, \
            f"LIKE '%log%' count={d.get('count')} matched={d.get('matched')}"
    ctx.assert_step(steps, "B3", b3_cond)

    s = ctx.find(steps, "B4")
    if s:
        d = s["data"]
        bad = [k for k in ("pool-100", "pool-1000", "pool-5000") if d.get(k) != 12]
        ctx.check("B4-pool", not bad, "pool IN 100/1000/5000 count 均=12（5000 占位符 FE prepare 也可用）",
                  f"异常项={bad} data={d}")

    s = ctx.find(hr_steps, "B4-hr")
    if s:
        d = s["data"]
        bad = [k for k in ("hr-100", "hr-1000", "hr-5000") if d.get(k) != 12]
        ctx.check("B4-hr", not bad, "HR IN 100/1000/5000 count 均=12",
                  f"异常项={bad} data={d}")

    s = ctx.find(steps, "B4b")
    if s:
        d = s["data"]
        ctx.check("B4b-pool", d.get("pool") == 12, f"字面量 IN(5000) simple-query count={d.get('pool')}",
                  f"pool={d.get('pool')}")

    s = ctx.find(steps, "B5")
    if s:
        d = s["data"]
        p = d.get("pool")
        ok = isinstance(p, list) and len(p) == 2 and p[0].startswith("100#6#1300.983789#true") \
            and p[1].startswith("200#4#70.000001#true")
        ctx.check("B5-pool", ok, f"GROUP BY+JOIN+IN+max(array_contains) pool rows={p}",
                  f"pool={p}")
    s = ctx.find(hr_steps, "B5-hr")
    if s:
        d = s["data"]
        rows = d.get("rows")
        ok = isinstance(rows, list) and len(rows) == 2 and rows[0].startswith("100#6#1300.983789#true") \
            and rows[1].startswith("200#4#70.000001#true")
        ctx.check("B5-hr", ok, f"HR 同 SQL 语义一致 rows={rows}（has3 经 1/0→true 归一）", f"rows={rows} err={s.get('error')}")

    def b5b_cond(s):
        d = s["data"]
        lit = str(d.get("literal-pool"))
        return d.get("tagsColumn") == [1, 3] and ("true" in lit.lower() or lit == "1"), \
            f"array_contains 字面量={lit}；ARRAY 列 array_contains(tags, ?) ids={d.get('tagsColumn')}"
    ctx.assert_step(steps, "B5b", b5b_cond)

    s = ctx.find(hr_steps, "B5b-hr")
    if s:
        lit = str(s["data"].get("literal"))
        ctx.check("B5b-hr", "true" in lit.lower() or lit == "1", f"HR array_contains 字面量={lit}", f"literal={lit}")


def run_types(ctx):
    steps = ctx.fetch_group("c")
    hr_steps = ctx.fetch_group("c-hr")

    s = ctx.find(steps, "C0")
    if s:
        d = s["data"]
        ctx.check("C0", d.get("timeZoneOptionExists") is False,
                  f"vertx MySQLConnectOptions 无 timeZone 参数（setter {d.get('setterCount')} 个）→ DATETIME 转换责任在应用层",
                  f"timeZone 参数存在={d.get('timeZoneOptionExists')}")

    def c1_cond(s):
        d = s["data"]
        return d.get("allEqual") and d.get("epochEqual"), \
            f"pool/jdbc 墙钟={d.get('pool')} epoch {d.get('poolEpochMs')}=={d.get('jdbcEpochMs')}"
    ctx.assert_step(steps, "C1", c1_cond)

    s = ctx.find(hr_steps, "C1-hr")
    if s:
        d = s["data"]
        if s.get("error"):
            ctx.log("C1-hr", "INFO", f"HR 实体读 DATETIME 列失败（重大发现）：{str(s.get('error'))[:180]}")
        else:
            ctx.check("C1-hr", "2024-06-01T08:00" in str(d.get("eventTime")),
                      f"HR 实体 eventTime={d.get('eventTime')}", f"data={d}")

    # D0 探针：HR 读 DATETIME 边界
    for sid, label in (("D0a", "native 直读→LocalDateTime"),
                       ("D0b", "native CAST AS CHAR→String"),
                       ("D0c", "native 直读→String")):
        st_ = ctx.find(hr_steps, sid)
        if st_:
            if st_.get("error"):
                ctx.log(sid, "INFO", f"{label} 失败：{str(st_.get('error'))[:180]}")
            else:
                ctx.log(sid, "INFO", f"{label} 成功：value={st_['data'].get('value')} type={st_['data'].get('type')}")

    s = ctx.find(steps, "C1b")
    if s:
        d = s["data"]
        base = d.get("serverTimezone=Asia/Shanghai(现网基线)", "")
        utc = d.get("connectionTimeZone=UTC", "")
        ok = "2024-06-01T08:00" in base and "1717200000000" in base and "1717228800000" in utc
        ctx.check("C1b", ok, "Connector/J 时区参数只影响 getTimestamp 纪元：上海=1717200000000 / UTC=1717228800000(+8h)，getObject(LocalDateTime) 墙钟不变",
                  f"基线={base} UTC={utc}")
        ctx.raw.append({"group": "c1b-matrix", "steps": [d]})

    def c2_cond(s):
        d = s["data"]
        return d.get("allEqual") and d.get("scale") == 6 and d.get("pool") == "123.456789", \
            f"DECIMAL(18,6) pool/jdbc={d.get('pool')} scale={d.get('scale')}"
    ctx.assert_step(steps, "C2", c2_cond)

    s = ctx.find(hr_steps, "C2-hr")
    if s:
        ctx.check("C2-hr", str(s["data"].get("eventValue")) == "123.456789",
                  f"HR DECIMAL={s['data'].get('eventValue')}", f"data={s['data']} err={s.get('error')}")

    def c3_cond(s):
        d = s["data"]
        return d.get("allExact") and d.get("expected") == 9007199254740993, \
            f"BIGINT 2^53+1 pool={d.get('pool')} jdbc={d.get('jdbc')} 精确"
    ctx.assert_step(steps, "C3", c3_cond)

    s = ctx.find(hr_steps, "C3-hr")
    if s:
        ctx.check("C3-hr", str(s["data"].get("eventId")) == "9007199254740993",
                  f"HR BIGINT={s['data'].get('eventId')} 精确", f"err={s.get('error')}")

    def c4_cond(s):
        d = s["data"]
        return (d.get("poolStatus") is None and d.get("jdbcStatusNull") is True
                and d.get("poolRemark") == "null"), \
            f"NULL pool/jdbc 读 null（pool remark={d.get('poolRemark')}）"
    ctx.assert_step(steps, "C4", c4_cond)

    s = ctx.find(hr_steps, "C4-hr")
    if s:
        d = s["data"]
        ctx.check("C4-hr", d.get("status") == "null" and d.get("remark") == "null",
                  f"HR NULL status={d.get('status')} remark={d.get('remark')}", f"data={d} err={s.get('error')}")

    def c5_cond(s):
        d = s["data"]
        ok = (d.get("jsonAllEqual") and d.get("rawType") == "JsonObject"
              and "😀" in str(d.get("poolPayload")))
        return ok, f"payload={d.get('poolPayload')}；pool JSON 列解码为 JsonObject（getString 抛 CCE），encode 后与 JDBC 文本一致"
    ctx.assert_step(steps, "C5", c5_cond)

    s = ctx.find(hr_steps, "C5-hr")
    if s:
        d = s["data"]
        ctx.check("C5-hr", "😀" in str(d.get("payload")), f"HR payload={d.get('payload')} attrsJson={d.get('attrsJson')}",
                  f"data={d} err={s.get('error')}")

    def c6_cond(s):
        d = s["data"]
        return d.get("poolRows") == 0 and d.get("jdbcEmpty") is True, \
            f"空结果集 pool={d.get('poolRows')} jdbcEmpty={d.get('jdbcEmpty')}"
    ctx.assert_step(steps, "C6", c6_cond)

    s = ctx.find(hr_steps, "C6-hr")
    if s:
        ctx.check("C6-hr", s["data"].get("rows") == 0, f"HR 空结果集 rows={s['data'].get('rows')}",
                  f"err={s.get('error')}")


def run_hr(ctx):
    steps = ctx.fetch_group("d")

    def d2_true_read(d):
        return d.get("found") and d.get("userName") == "lab-写入"

    def d1_cond(s):
        return s["ok"] and s["data"].get("count", 0) >= 5, \
            f"Panache 拦截器路径（@WithSession on eventloop）count={s['data'].get('count')}"
    ctx.assert_step(steps, "D1", d1_cond)

    s = ctx.find(steps, "D2-entity")
    if s:
        if s.get("error"):
            ctx.log("D2-entity", "INFO", f"实体新 session 真 find 失败（DATETIME 列提取，非缓存假读）：{str(s.get('error'))[:150]}")
        else:
            ctx.check("D2-entity", d2_true_read(s["data"]), f"实体真读 userName={s['data'].get('userName')}")
    s = ctx.find(steps, "D2-native")
    if s:
        d = s["data"]
        ctx.check("D2-native", s["ok"] and d.get("userName") == "lab-写入" and "2024-06-01T08:00" in str(d.get("gmtCreate")),
                  f"persist 落库验证（native 读）userName={d.get('userName')} gmtCreate={d.get('gmtCreate')}({d.get('gmtCreateType')})",
                  f"data={d} err={s.get('error')}")

    def d2b_cond(s):
        d = s["data"]
        return (d.get("userNameAfter") == "alice-被覆盖" and d.get("emailAfter") == "null"
                and d.get("statusAfter") == "null"), \
            f"unique 模型重复主键 persist 静默 upsert 整行覆盖 userName={d.get('userNameAfter')} email={d.get('emailAfter')} status={d.get('statusAfter')}"
    ctx.assert_step(steps, "D2b", d2b_cond)

    s = ctx.find(steps, "D2c-same-session")
    if s:
        ctx.log("D2c-same-session", "INFO", f"同 session 双 persist 被 Hibernate 按 @Id 拦截：{str(s.get('error'))[:150]}")
    s = ctx.find(steps, "D2c")
    if s:
        ctx.check("D2c", s["data"].get("rowsWithSameId") == 2,
                  f"分 session 双 persist → Doris append 语义 rows={s['data'].get('rowsWithSameId')}",
                  f"rows={s['data'].get('rowsWithSameId')} err={s.get('error')}")

    s = ctx.find(steps, "D3a-entity")
    if s:
        ctx.log("D3a-entity", "INFO", f"实体 find(新 session) 真读失败：{str(s.get('error'))[:150]}")
    s = ctx.find(steps, "D3a")
    if s:
        ctx.check("D3a", s["data"].get("balanceAfter") == "555.555555",
                  f"native UPDATE 读回 balance={s['data'].get('balanceAfter')}",
                  f"data={s['data']} err={s.get('error')}")

    s = ctx.find(steps, "D3b")
    if s:
        if s.get("error"):
            ctx.log("D3b", "INFO", f"HR native（prepare 路径）DELETE 断连：{str(s.get('error'))[:150]}（对照：G6 pool simple-query DELETE 正常）")
        else:
            ctx.check("D3b", s["data"].get("rowsAfterDelete") == 0,
                      f"unique 模型 native DELETE 后 rows={s['data'].get('rowsAfterDelete')}", f"data={s['data']}")

    s = ctx.find(steps, "D3c")
    if s:
        if s.get("error"):
            ctx.log("D3c", "INFO", f"HR native（prepare 路径）DELETE 断连（三表一致）：{str(s.get('error'))[:150]}")
        else:
            ctx.check("D3c", s["data"].get("rowsAfterDelete") == 0,
                      f"duplicate 模型键列 DELETE 后 rows={s['data'].get('rowsAfterDelete')}", f"data={s['data']}")

    s = ctx.find(steps, "D3d")
    if s:
        if s.get("error"):
            ctx.log("D3d", "INFO", f"事务内 DELETE 被 Doris 拒：{str(s.get('error'))[:180]}")
        else:
            ctx.check("D3d", s["ok"], f"事务内 DELETE 可执行 affected={s['data']}", f"data={s['data']}")
    s = ctx.find(steps, "D3d-native")
    if s:
        if s.get("error"):
            ctx.log("D3d-native", "INFO", f"aggregate 模型 native DELETE 同样断连（三表一致，prepare 路径 DELETE 通病）：{str(s.get('error'))[:150]}")
        else:
            ctx.check("D3d-native", s["ok"], f"aggregate DELETE affected={s['data'].get('affectedKeyCond')}", f"data={s['data']}")

    def d4_cond(s):
        d = s["data"]
        return d.get("rows") == 6 and d.get("firstName") == "login", f"session native query rows={d.get('rows')}"
    ctx.assert_step(steps, "D4", d4_cond)

    s = ctx.find(steps, "D4b")
    if s:
        d = s["data"]
        ctx.check("D4b", s["ok"], f"native INSERT executeUpdate affected={d.get('affected')}（DATETIME 绑定参数写路径）",
                  f"err={s.get('error')}")

    s = ctx.find(steps, "D4c")
    if s:
        if s.get("error"):
            ctx.log("D4c", "INFO", f"aggregate 模型 @EmbeddedId findById 被拒（生成 SQL 形态 FE prepare 不支持）：{str(s.get('error'))[:200]}")
        else:
            d = s["data"]
            ctx.check("D4c", d.get("found") and d.get("total") == 15 and d.get("last") == 5 and d.get("max") == 10,
                      f"aggregate 模型实体读 total={d.get('total')}/last={d.get('last')}/max={d.get('max')}", f"data={d}")

    s = ctx.find(steps, "D5a")
    if s:
        if s.get("error"):
            ctx.log("D5a", "INFO", f"@WithTransaction 内 prepare INSERT 被 Doris 拒：{str(s.get('error'))[:180]}")
        else:
            ctx.check("D5a", s["data"].get("rowsAfterCommit") == 2,
                      f"@WithTransaction 双 INSERT COMMIT 后 rows={s['data'].get('rowsAfterCommit')}", f"data={s['data']}")

    s = ctx.find(steps, "D5b")
    if s:
        d = s["data"]
        ctx.log("D5b", "INFO",
                f"事务内 INSERT 被拒后 rowsAfterRollback={d.get('rowsAfterRollback')}（行不在=INSERT 未落库而非回滚成功；err={str(s.get('error'))[:120]}）")

    s = ctx.find(steps, "D5c")
    if s:
        if s.get("error"):
            ctx.log("D5c", "INFO", f"事务内 UPDATE（merge 前置 find 炸于 DATETIME）：{str(s.get('error'))[:150]}")
        else:
            ctx.log("D5c", "INFO", f"事务内 UPDATE 后 userName={s['data'].get('userNameAfterRollback')}（回滚有效性观测）")

    s = ctx.find(steps, "D5d")
    if s:
        if s.get("error"):
            ctx.log("D5d", "INFO", f"duplicate 模型事务内 INSERT 被拒：{str(s.get('error'))[:150]}")
        else:
            ctx.log("D5d", "INFO", f"duplicate 模型事务内 INSERT 成功 data={s['data']}")

    def d5e_cond(s):
        if s["data"].get("rejected"):
            return "This is in a transaction" in str(s.get("error")), \
                f"Doris 事务内拒绝 SELECT err={str(s.get('error'))[:160]}"
        return False, f"事务内 SELECT 未被拒 count={s['data'].get('count')}"
    ctx.assert_step(steps, "D5e", d5e_cond)

    s = ctx.find(steps, "D6")
    if s:
        d = s["data"]
        if d.get("rejected") or d.get("limitShape") == "parameterized-limit-rejected":
            ctx.log("D6", "INFO", f"HQL 分页（Panache findAll().page() 同路径）被 FE 拒（limit ?,? 参数化）：{str(s.get('error'))[:160]}")
        else:
            ok = len(d.get("page0", [])) == 2 and len(d.get("page1", [])) == 2
            ctx.check("D6", ok, f"HQL 分页可用 page0={d.get('page0')} page1={d.get('page1')}", f"data={d}")

    def d6b_cond(s):
        raw = s["data"].get("ids", [])
        if isinstance(raw, str):
            raw = raw.strip("[]").replace(" ", "").split(",") if raw.strip("[]") else []
        try:
            ids = [int(x) for x in raw]
        except (ValueError, TypeError):
            ids = []
        return ids == [300, 400], f"原生字面量 LIMIT 分页（workaround）ids={ids}"
    ctx.assert_step(steps, "D6b", d6b_cond)


def run_compare(ctx):
    # D 组可能因环境故障（如 BE 内存超限）留下脏数据，e 组前强制重灌种子
    lab.post("/scenario/setup?reseed=true", timeout=120)
    steps = ctx.fetch_group("e")
    hr_steps = ctx.fetch_group("e-hr")

    s = ctx.find(steps, "E1")
    if s:
        d = s["data"]
        bad = []
        for key in ("id1", "id3", "id4", "idbig"):
            pair = {d.get(f"{key}-pool"), d.get(f"{key}-jdbc")}
            if len(pair) != 1 or None in pair:
                bad.append(key)
        ctx.check("E1-pool-jdbc", not bad,
                  f"种子行 {{1,3,4,2^53+1}} pool/jdbc 逐字段一致（含 NULL/空串/emoji/DECIMAL/DATETIME/JSON）",
                  f"不一致项={bad} data={json.dumps(d, ensure_ascii=False)[:500]}")

    s = ctx.find(hr_steps, "E1-hr")
    if s:
        d = s["data"]
        e1 = ctx.find(steps, "E1")
        pool_data = e1.get("data", {}) if e1 else {}
        def norm_num(canon):
            # DECIMAL scale 归一（hr native 保留 wire scale，pool/jdbc 最简形态）
            parts = canon.split("|")
            if len(parts) > 4:
                try:
                    from decimal import Decimal
                    parts[4] = str(Decimal(parts[4]).normalize())
                except Exception:
                    pass
            return "|".join(parts)
        bad = []
        for key in ("id1", "id3", "id4", "idbig"):
            hr = norm_num(str(d.get(key, "<missing>")))
            pool = norm_num(str(pool_data.get(f"{key}-pool", "<missing>")))
            if hr != pool:
                bad.append(f"{key}(hr={hr[:80]} vs pool={pool[:80]})")
        ctx.check("E1-hr", not bad,
                  "HR native 投影与 pool 逐字段一致（含 DATETIME 元素 LocalDateTime 形态）",
                  f"不一致: {bad} err={s.get('error')}")

    s = ctx.find(steps, "E2")
    if s:
        d = s["data"]
        trio = {json.dumps(d.get("pool")), json.dumps(d.get("jdbc"))}
        ctx.check("E2-pool-jdbc", len(trio) == 1 and d.get("pool") and "ERR" not in json.dumps(d),
                  f"复杂聚合 SQL pool/jdbc 逐行一致 rows={d.get('pool')}",
                  f"pool={d.get('pool')} jdbc={d.get('jdbc')}")
    s = ctx.find(hr_steps, "E2-hr")
    if s:
        d = s["data"]
        pool_rows = ctx.find(steps, "E2").get("data", {}).get("pool") if ctx.find(steps, "E2") else None
        ctx.check("E2-hr", d.get("rows") == pool_rows,
                  f"HR 同 SQL 三方一致 rows={d.get('rows')}",
                  f"pool={pool_rows} hr={d.get('rows')} err={s.get('error')}")

    s = ctx.find(steps, "E3")
    if s:
        d = s["data"]
        ctx.check("E3-pool-jdbc", d.get("poolRows") == 0 and d.get("jdbcRows") == 0,
                  f"空结果集 pool={d.get('poolRows')} jdbc={d.get('jdbcRows')}", f"data={d}")
    s = ctx.find(hr_steps, "E3-hr")
    if s:
        ctx.check("E3-hr", s["data"].get("rows") == 0, f"HR 空结果集 rows={s['data'].get('rows')}",
                  f"err={s.get('error')}")


def run_timeout(ctx):
    steps = ctx.fetch_group("f")

    def f1_cond(s):
        d = s["data"]
        return d.get("cancelled") and d.get("elapsedMs", 99999) < 4000 and d.get("poolCleanAfter") \
            and "query timeout" in str(d.get("err")), \
            f"SET_VAR hint ~{d.get('elapsedMs')}ms FE 取消（query timeout），连接干净归还池"
    ctx.assert_step(steps, "F1", f1_cond)

    def f2_cond(s):
        d = s["data"]
        return d.get("cancelled") and d.get("elapsedMs", 99999) < 4000 and "query timeout" in str(d.get("err")), \
            f"session 变量 ~{d.get('elapsedMs')}ms 取消（池上勿用，会污染会话）"
    ctx.assert_step(steps, "F2", f2_cond)

    s = ctx.find(steps, "F3")
    if s:
        d = s["data"]
        # 记录型场景：观测客户端取消的池级效应（判据细节见矩阵）
        verdict = "取消未传导（普通查询排队等 sleep 完成才返回）" if d.get("probeWaitMs", 0) >= 1500 else "普通查询快速返回（连接未被占满）"
        probe = "probe 正常返回" if d.get("poolUsable") else f"probe 异常/错位（{d.get('probeWaitMs')}ms 处拿到非预期结果——被放弃命令的连接恢复期现象）"
        ctx.log("F3", "INFO",
                f"本地 2s 超时放弃 8 条 sleep(6)：{verdict}；probeWaitMs={d.get('probeWaitMs')}，{probe}；"
                f"FE runningQueryRows={d.get('runningQueryRows')}")

    def f4_cond(s):
        d = s["data"]
        return d.get("cancelled") and d.get("sameConnUsable") and d.get("propagated") is True, \
            f"JDBC setQueryTimeout ~{d.get('elapsedMs')}ms 取消且 KILL QUERY 传导 FE（sleep(5) 残留={d.get('lingeringAfter')}），同连接仍可用"
    ctx.assert_step(steps, "F4", f4_cond)

    s = ctx.find(steps, "F5")
    if s:
        d = s["data"]
        ctx.check("F5", s["ok"] and d.get("rows", 0) >= 1,
                  f"SHOW PROCESSLIST 经 preparedQuery（prepare 路径）可用 rows={d.get('rows')} sample={str(d.get('sample'))[:120]}",
                  f"err={s.get('error')}")


def run_extras(ctx):
    steps = ctx.fetch_group("g")

    def g1_cond(s):
        d = s["data"]
        return s["ok"] and d.get("seededCount") == 5, f"preparedBatch 5 行 affected={d.get('affected')} 落库={d.get('seededCount')}"
    ctx.assert_step(steps, "G1", g1_cond)

    def g2_cond(s):
        d = s["data"]
        return d.get("accepted") is False and "mismatched input 'ON'" in str(d.get("err")), \
            f"ON DUPLICATE KEY 被 FE 拒 err={str(d.get('err'))[:160]}"
    ctx.assert_step(steps, "G2", g2_cond)

    def g3_cond(s):
        d = s["data"]
        return d.get("finalRows") == 1 and d.get("failures") == 0, \
            f"10 并发同键 upsert 全成功终态单行 winner={d.get('winner')} 耗时{d.get('elapsedMs')}ms（MySQL 同场景必 DuplicateKeyException）"
    ctx.assert_step(steps, "G3", g3_cond)

    def g4_cond(s):
        d = s["data"]
        cols = d.get("cols", [])
        return len(cols) == 10 and "event_id" in cols, "information_schema 参数化查询 10 列"
    ctx.assert_step(steps, "G4", g4_cond)

    def g5_cond(s):
        d = s["data"]
        return d.get("total") == 14 and d.get("last") == 13 and d.get("max") == 13, \
            "aggregate 模型 INSERT 即聚合：SUM 1+13=14 / REPLACE=13 / MAX=13"
    ctx.assert_step(steps, "G5", g5_cond)

    def g6_cond(s):
        d = s["data"]
        return d.get("written") == d.get("readBack"), f"emoji+中文 pool 绑定写→HR 读一致: {d.get('readBack')}"
    ctx.assert_step(steps, "G6", g6_cond)


RUNNERS = {
    "a": run_pool,
    "health": run_health,
    "b": run_binding,
    "c": run_types,
    "d": run_hr,
    "e": run_compare,
    "f": run_timeout,
    "g": run_extras,
}


def main():
    groups = sys.argv[1:] or ["a", "health", "b", "c", "d", "e", "f", "g"]
    ctx = Ctx()

    st, body = lab.wait_healthy()
    if st is None:
        print("[e2e] 应用未就绪（localhost:8081），先运行 run-e2e.sh", file=sys.stderr)
        sys.exit(2)
    if st != 200:
        print(f"[e2e] readiness 非 UP（HTTP {st}）：{str(body)[:200]}", file=sys.stderr)
        sys.exit(2)
    st, body = lab.post("/scenario/setup", timeout=120)
    if st != 200:
        print(f"[e2e] 建表种子失败：HTTP {st} {str(body)[:200]}", file=sys.stderr)
        sys.exit(2)
    print(f"[e2e] setup: {body}")

    for g in groups:
        runner = RUNNERS.get(g)
        if not runner:
            print(f"[e2e] 未知组 {g}，可选: {list(RUNNERS)}", file=sys.stderr)
            continue
        runner(ctx)

    passed, failed, total = lab.render_report(ctx)
    print(f"\n[e2e] 汇总: {total} 用例，PASS {passed}，FAIL {failed}；报告 e2e/report/report.md")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
