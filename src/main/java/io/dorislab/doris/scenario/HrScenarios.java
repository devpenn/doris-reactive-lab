package io.dorislab.doris.scenario;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.reactive.mutiny.Mutiny;

import io.dorislab.doris.hr.LabAggMetric;
import io.dorislab.doris.hr.LabAggMetricId;
import io.dorislab.doris.hr.LabDupEvent;
import io.dorislab.doris.hr.LabHrService;
import io.dorislab.doris.hr.LabUkUser;
import io.dorislab.doris.support.LabLog;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.RowSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 场景四（D 组）：Hibernate Reactive / Panache 专项。
 * <p>
 * 线程模型：全链跑在 REST eventloop 订阅点（Panache 拦截器与 HR 客户端唯一可靠线程），
 * 数据恢复用 dorispool 响应式 reseed（不阻塞不切线程）。
 * 已证结论（矩阵）：实体路径读含 DATETIME 列的表必炸（Unable to extract JDBC value）；
 * persist 后同 session find 命中一级缓存是「假读」——所有实体读观测必须开新 session。
 */
@ApplicationScoped
public class HrScenarios {

    @Inject
    LabHrService svc;

    @Inject
    Mutiny.SessionFactory sf;

    @Inject
    @io.quarkus.reactive.datasource.ReactiveDataSource("dorispool")
    MySQLPool pool;

    public Uni<List<StepResult>> runAll() {
        List<StepResult> out = new ArrayList<>();
        return step(out, "D1", svc.userCount().map(c -> Map.<String, Object>of(
                        "count", c, "path", "panache-interceptor-on-eventloop")))
                // D2：persist 后开新 session 真 find（绕开一级缓存）——预期炸（DATETIME 列提取），native 投影验证落库
                .chain(v -> stepFailAllowed(out, "D2-entity", sf.withSession(s -> s.persist(user(9001L, "lab-写入")).chain(x -> s.flush()))
                        .chain(x -> sf.withSession(s2 -> s2.find(LabUkUser.class, 9001L)))
                        .map(u -> Map.<String, Object>of(
                                "found", u != null,
                                "userName", u == null ? "" : String.valueOf(u.userName),
                                "gmtCreate", u == null ? "" : String.valueOf(u.gmtCreate)))))
                .chain(v -> step(out, "D2-native", sf.withSession(s -> s.createNativeQuery(
                        "SELECT user_name, gmt_create FROM lab_uk_users WHERE user_id = 9001", Object[].class)
                        .getSingleResult())
                        .map(row -> {
                            Object dt = HrLegScenarios.normalizeCell(row[1]);
                            return Map.<String, Object>of("userName", String.valueOf(HrLegScenarios.normalizeCell(row[0])),
                                    "gmtCreate", String.valueOf(dt),
                                    "gmtCreateType", dt == null ? "null" : dt.getClass().getSimpleName());
                        })))
                // D2b：unique 模型 INSERT=upsert（显式 null 字段实体，验证整行覆盖）
                .chain(v -> stepReseed(out, "D2b", sf.withSession(s -> s.persist(
                                new LabUkUser(1001L, "alice-被覆盖", null, null, null, null, null, null))
                                .chain(x -> s.flush()))
                        .chain(x -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT user_name, email, balance, status, gmt_create FROM lab_uk_users WHERE user_id = 1001",
                                Object[].class).getSingleResult()))
                        .map(row -> Map.<String, Object>of(
                                "userNameAfter", String.valueOf(HrLegScenarios.normalizeCell(row[0])),
                                "emailAfter", String.valueOf(HrLegScenarios.normalizeCell(row[1])),
                                "balanceAfter", String.valueOf(row[2]),
                                "statusAfter", String.valueOf(row[3]),
                                "gmtCreateAfter", String.valueOf(row[4])))))
                // D2c：duplicate 模型 append——同 session 双 persist 被 Hibernate 按 @Id 拦截；分 session 后可达 Doris append
                .chain(v -> stepFailAllowed(out, "D2c-same-session", sf.withSession(s -> s.persist(dup(90002L, "lab-append", BigDecimal.ONE))
                        .chain(x -> s.persist(dup(90002L, "lab-append", BigDecimal.TWO)))
                        .map(x -> Map.<String, Object>of("inserted", true)))))
                // 注意：嵌套 withSession 会复用同一 Quarkus session（同 id 双 persist 会被拦），
                // 必须在外层平铺三个独立 withSession（前一个关闭后再开下一个）
                .chain(v -> stepReseed(out, "D2c", sf.withSession(s -> s.persist(dup(90002L, "lab-append", BigDecimal.ONE))
                        .chain(x -> s.flush()))
                        .chain(v1 -> sf.withSession(s -> s.persist(dup(90002L, "lab-append", BigDecimal.TWO))
                                .chain(x -> s.flush())))
                        .chain(v2 -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT count(*) FROM lab_dup_events WHERE event_id = 90002", Long.class).getSingleResult()))
                        .map(c -> Map.<String, Object>of("rowsWithSameId", c))))
                // D3a：实体 find 新 session 真 SELECT 炸（gmt_create position 4）→ native UPDATE 形态验证
                .chain(v -> stepFailAllowed(out, "D3a-entity", sf.withSession(s -> s.find(LabUkUser.class, 200L)
                        .chain(u -> {
                            u.balance = new BigDecimal("555.555555");
                            return s.merge(u);
                        })
                        .chain(x -> sf.withSession(s2 -> s2.find(LabUkUser.class, 200L)))
                        .map(u -> Map.<String, Object>of("balanceAfter", String.valueOf(u.balance))))))
                .chain(v -> stepReseed(out, "D3a", sf.withSession(s -> s.createNativeQuery(
                        "UPDATE lab_uk_users SET balance = ? WHERE user_id = ?")
                        .setParameter(1, new BigDecimal("555.555555")).setParameter(2, 200L)
                        .executeUpdate())
                        .chain(x -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT balance FROM lab_uk_users WHERE user_id = 200", BigDecimal.class).getSingleResult()))
                        .map(bal -> Map.<String, Object>of("balanceAfter", String.valueOf(bal)))))
                // D3b：unique 模型 native DELETE
                .chain(v -> step(out, "D3b", sf.withSession(s -> s.createNativeQuery(
                           "INSERT INTO lab_uk_users (user_id, user_name) VALUES (9003, 'lab-删除')")
                        .executeUpdate()
                        .chain(x -> s.createNativeQuery("DELETE FROM lab_uk_users WHERE user_id = 9003").executeUpdate())
                        .chain(x -> s.createNativeQuery("SELECT count(*) FROM lab_uk_users WHERE user_id = 9003", Long.class).getSingleResult()))
                        .map(c -> Map.<String, Object>of("rowsAfterDelete", c))))
                // D3c：duplicate 模型键列 DELETE
                .chain(v -> stepReseed(out, "D3c", sf.withSession(s -> s.createNativeQuery(
                        "DELETE FROM lab_dup_events WHERE event_id = 11").executeUpdate())
                        .chain(x -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT count(*) FROM lab_dup_events WHERE event_id = 11", Long.class).getSingleResult()))
                        .map(c -> Map.<String, Object>of("rowsAfterDelete", c))))
                // D3d：aggregate 模型事务内 DELETE（观测 Doris 对事务内 DELETE 的支持度）
                .chain(v -> stepFailAllowed(out, "D3d", sf.withTransaction((s, tx) ->
                                s.createNativeQuery("DELETE FROM lab_agg_metrics WHERE total_value > 0").executeUpdate()
                                        .chain(n1 -> s.createNativeQuery(
                                                "DELETE FROM lab_agg_metrics WHERE biz_date = '2024-06-01' AND company_id = 100 AND metric_name = 'calls'")
                                                .executeUpdate().map(n2 -> Map.<String, Object>of(
                                                        "affectedValueCond", n1, "affectedKeyCond", n2))))))
                // D3d-native：非事务形态 aggregate DELETE
                .chain(v -> stepReseed(out, "D3d-native", sf.withSession(s -> s.createNativeQuery(
                        "DELETE FROM lab_agg_metrics WHERE biz_date = '2024-06-01' AND company_id = 100 AND metric_name = 'calls'")
                        .executeUpdate())
                        .map(n -> Map.<String, Object>of("affectedKeyCond", n))))
                // D4：session 级 native 读
                .chain(v -> step(out, "D4", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_id, event_name, event_value FROM lab_dup_events WHERE company_id = ? ORDER BY event_id",
                        Object[].class).setParameter(1, 100L).getResultList())
                        .map(rows -> Map.<String, Object>of(
                                "rows", rows.size(),
                                "firstName", rows.isEmpty() ? "" : String.valueOf(HrLegScenarios.normalizeCell(rows.get(0)[1])),
                                "firstValue", rows.isEmpty() ? "" : String.valueOf(rows.get(0)[2])))))
                // D4b：native INSERT（含 DATETIME 参数绑定）
                .chain(v -> step(out, "D4b", sf.withSession(s -> s.createNativeQuery(
                        "INSERT INTO lab_uk_users (user_id, user_name, email, balance, status, gmt_create, gmt_modified, ext_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                        .setParameter(1, 9004L).setParameter(2, "native-写").setParameter(3, "native-写@lab.local")
                        .setParameter(4, new BigDecimal("9.004")).setParameter(5, 1)
                        .setParameter(6, LocalDateTime.of(2024, 6, 1, 8, 0, 0))
                        .setParameter(7, LocalDateTime.of(2024, 6, 1, 9, 0, 0))
                        .setParameter(8, "{\"lab\":true}")
                        .executeUpdate())
                        .map(affected -> Map.<String, Object>of("affected", affected))))
                // D4c：aggregate 模型 @EmbeddedId 实体 findById（观测生成 SQL 是否被 FE prepare 拒）
                .chain(v -> stepFailAllowed(out, "D4c", sf.withSession(s -> s.find(LabAggMetric.class,
                        new LabAggMetricId(LocalDate.of(2024, 6, 1), 100L, "calls")))
                        .map(m -> Map.<String, Object>of(
                                "found", m != null,
                                "total", m == null ? -1 : m.totalValue,
                                "last", m == null ? -1 : m.lastValue,
                                "max", m == null ? -1 : m.maxValue))))
                // D5：@WithTransaction 事务语义（观测 Doris 对事务内 prepare DML 的支持度）
                .chain(v -> stepFailAllowed(out, "D5a", svc.txnInsertTwoUsers(LabHrService.user(9101L, "txn-A"), LabHrService.user(9102L, "txn-B"))
                        .chain(x -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT count(*) FROM lab_uk_users WHERE user_id IN (9101, 9102)", Long.class).getSingleResult()))
                        .map(c -> Map.<String, Object>of("rowsAfterCommit", c))))
                .chain(v -> stepFailAllowed(out, "D5b", sf.withTransaction((s, tx) ->
                                s.persist(user(9103L, "txn-回滚实验"))
                                        .chain(x -> Uni.createFrom().<Void>failure(new IllegalStateException("LAB-ROLLBACK-PROBE"))))
                        .onFailure().recoverWithItem((Void) null)
                        .chain(x -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT count(*) FROM lab_uk_users WHERE user_id = 9103", Long.class).getSingleResult()))
                        .map(c -> Map.<String, Object>of("rowsAfterRollback", c))))
                .chain(v -> stepReseed(out, "D5c", sf.withTransaction((s, tx) ->
                                s.find(LabUkUser.class, 300L)
                                        .chain(u -> {
                                            u.userName = "卡罗尔-事务回滚实验";
                                            return s.merge(u).map(x -> (Void) null);
                                        })
                                        .chain(x -> Uni.createFrom().<Void>failure(new IllegalStateException("LAB-ROLLBACK-PROBE"))))
                        .onFailure().recoverWithItem((Void) null)
                        .chain(x -> sf.withSession(s -> s.createNativeQuery(
                                "SELECT user_name FROM lab_uk_users WHERE user_id = 300", String.class).getSingleResult()))
                        .map(name -> Map.<String, Object>of("userNameAfterRollback", String.valueOf(HrLegScenarios.normalizeCell(name))))))
                .chain(v -> stepFailAllowed(out, "D5d", sf.withTransaction((s, tx) ->
                        s.persist(dup(91001L, "txn-dup", BigDecimal.ONE))
                                .map(x -> Map.<String, Object>of("inserted", Boolean.TRUE)))))
                .chain(v -> stepFailAllowed(out, "D5e", sf.withTransaction((s, tx) ->
                        s.createNativeQuery("SELECT count(*) FROM lab_uk_users", Long.class).getSingleResult())
                        .map(c -> Map.<String, Object>of("count", c, "rejected", false))))
                // D6：HQL 分页（Panache findAll().page() 同路径）
                .chain(v -> stepFailAllowed(out, "D6", sf.withSession(s -> s.createQuery("FROM LabUkUser", LabUkUser.class)
                        .setFirstResult(0).setMaxResults(2).getResultList()
                        .chain(p0 -> s.createQuery("FROM LabUkUser", LabUkUser.class)
                                .setFirstResult(2).setMaxResults(2).getResultList()
                                .map(p1 -> Map.<String, Object>of(
                                        "page0", p0.stream().map(u -> u.userId).toList(),
                                        "page1", p1.stream().map(u -> u.userId).toList(),
                                        "limitShape", "inlined-or-supported"))))))
                // D6b：原生字面量 LIMIT 分页（workaround）
                .chain(v -> step(out, "D6b", sf.withSession(s -> s.createNativeQuery(
                        "SELECT user_id FROM lab_uk_users ORDER BY user_id LIMIT 2 OFFSET 2", Long.class)
                        .getResultList()
                        .map(ids -> Map.<String, Object>of("ids", ids)))))
                .map(v -> {
                    sanitize(out);
                    return out;
                });
    }

    /** 序列化护栏：data 里混入非 JSON 值时替换为其字符串形态并标注 */
    private static void sanitize(List<StepResult> out) {
        for (int i = 0; i < out.size(); i++) {
            StepResult r = out.get(i);
            boolean dirty = false;
            Map<String, Object> fixed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> en : r.data().entrySet()) {
                Object v = en.getValue();
                if (v instanceof Uni || v instanceof Map || v instanceof List) {
                    fixed.put(en.getKey(), String.valueOf(v));
                    dirty = true;
                } else if (v instanceof Number || v instanceof Boolean || v instanceof String || v == null) {
                    fixed.put(en.getKey(), v);
                } else {
                    fixed.put(en.getKey(), String.valueOf(v) + "<" + v.getClass().getSimpleName() + ">");
                    dirty = true;
                }
            }
            if (dirty) {
                out.set(i, new StepResult(r.id(), r.route(), r.ok(), r.error(), fixed));
            }
        }
    }

    // ─── 链式步骤助手（全 eventloop，不 await 不切线程） ───

    private Uni<Void> step(List<StepResult> out, String id, Uni<Map<String, Object>> work) {
        return work.onItem().transform(data -> {
            out.add(StepResult.pass(id, "hr", data));
            return (Void) null;
        }).onFailure().recoverWithItem(e -> {
            out.add(StepResult.broken(id, "hr", LabLog.brief((Throwable) e)));
            return (Void) null;
        });
    }

    private Uni<Void> stepReseed(List<StepResult> out, String id, Uni<Map<String, Object>> work) {
        return step(out, id, work).chain(v -> reseedUni());
    }

    /** 探针步骤：失败是合法观测，报错原文进 error 交 e2e 断言 */
    private Uni<Void> stepFailAllowed(List<StepResult> out, String id, Uni<Map<String, Object>> work) {
        return work.onItem().transform(data -> {
            out.add(StepResult.pass(id, "hr", data));
            return (Void) null;
        }).onFailure().recoverWithItem(e -> {
            out.add(new StepResult(id, "hr", true, LabLog.brief((Throwable) e), Map.of("rejected", true)));
            return (Void) null;
        });
    }

    /** 响应式 reseed（dorispool 顺序链；失败记录不炸链，数据状态由断言暴露） */
    private Uni<Void> reseedUni() {
        Uni<Void> chain = Uni.createFrom().voidItem();
        List<String> sqls = new ArrayList<>();
        sqls.add("TRUNCATE TABLE lab_dup_events");
        sqls.add("TRUNCATE TABLE lab_uk_users");
        sqls.add("TRUNCATE TABLE lab_agg_metrics");
        io.dorislab.doris.support.LabSetup.seedStatements().forEach(sqls::add);
        for (String sql : sqls) {
            chain = chain.chain(v -> pool.query(sql).execute()
                    .map((RowSet<io.vertx.mutiny.sqlclient.Row> rs) -> (Void) null)
                    .onFailure().recoverWithItem(e -> {
                        LabLog.record("RESEED", "hr", "FAIL", LabLog.brief((Throwable) e));
                        return (Void) null;
                    }));
        }
        return chain;
    }

    private LabUkUser user(long id, String name) {
        return LabHrService.user(id, name);
    }

    private LabDupEvent dup(long id, String name, BigDecimal v) {
        return new LabDupEvent(id, LocalDateTime.of(2024, 6, 10, 8, 0, 0), 100L, name, v, null, null, 1, null);
    }
}
