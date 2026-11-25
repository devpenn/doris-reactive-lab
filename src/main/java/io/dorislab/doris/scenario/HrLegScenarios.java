package io.dorislab.doris.scenario;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.hibernate.reactive.mutiny.Mutiny;

import io.dorislab.doris.hr.LabDupEvent;
import io.dorislab.doris.support.LabLog;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * HR 腿专用场景（eventloop 限定）：B/C/E 三组的 Hibernate Reactive 数据腿。
 * HR 客户端（含裸 Mutiny.SessionFactory）只能在 eventloop（flagged safe Vert.x sub-context）上跑
 * （worker 触发 HR000068、裸线程触发 safe sub-context 检查失败，两轮实跑实证），
 * 因此本 bean 的方法全部以 Uni 链在 REST eventloop 订阅点上执行，禁止 .await()。
 */
@ApplicationScoped
public class HrLegScenarios {

    @Inject
    Mutiny.SessionFactory sf;

    /** B 组 HR 腿探针执行器：单列 SELECT，返回归一化标量 */
    private Uni<Map<String, Object>> probeHr(String sql, List<Object> params) {
        return sf.withSession(s -> {
            var q = s.createNativeQuery(sql, Object[].class);
            for (int i = 0; i < params.size(); i++) {
                q = q.setParameter(i + 1, params.get(i));
            }
            return q.getResultList();
        }).map(rows -> {
            Object first = rows.isEmpty() ? null : rows.get(0);
            Object cell = (first instanceof Object[] arr && arr.length > 0) ? arr[0] : first;
            return Map.<String, Object>of("rows", rows.size(),
                    "first", String.valueOf(normalizeCell(cell)),
                    "elemType", cell == null ? "null" : normalizeCell(cell).getClass().getSimpleName());
        });
    }

    /** ClobProxy（HR 对文本回显的包装）归一为字符串内容 */
    static Object normalizeCell(Object cell) {
        if (cell instanceof java.sql.Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (Exception e) {
                return "Clob<unreadable>";
            }
        }
        return cell;
    }

    /** 布尔/数值 1 归一（array_contains 在 native 路径返回 1/0） */
    static String normalizeBool(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return String.valueOf(b);
        if (v instanceof Number n) return String.valueOf(n.intValue() != 0);
        return String.valueOf(v);
    }

    // ─── B 组 HR 腿 ───

    public Uni<List<StepResult>> runBHr() {
        List<StepResult> out = new ArrayList<>();
        return b1(out)
                .chain(v -> b1c(out))
                .chain(v -> b1d(out))
                .chain(v -> b2(out))
                .chain(v -> b4(out))
                .chain(v -> b5(out))
                .chain(v -> b5b(out))
                .map(v -> out);
    }

    private Uni<Void> add(List<StepResult> out, String id, Uni<Map<String, Object>> work) {
        return work.onItem().transform(data -> {
            out.add(StepResult.pass(id, "hr", data));
            return (Void) null;
        }).onFailure().recoverWithItem(e -> {
            out.add(StepResult.broken(id, "hr", LabLog.brief((Throwable) e)));
            return (Void) null;
        });
    }

    private Uni<Void> b1(List<StepResult> out) {
        return add(out, "B1-hr", probeHr("SELECT 1 AS x LIMIT ?, ?", List.of(0L, 1))
                .onItem().transform(data -> Map.<String, Object>of(
                        "ok", false, "err", "expected-fail", "probe", data)));
    }

    private Uni<Void> b1c(List<StepResult> out) {
        return add(out, "B1c-hr", sf.withSession(s -> s.createNativeQuery(
                "SELECT event_id FROM lab_dup_events WHERE company_id = ? ORDER BY event_id LIMIT 3 OFFSET 1",
                Long.class).setParameter(1, 100L).getResultList())
                .map(rows -> Map.<String, Object>of("ids", rows)));
    }

    private Uni<Void> b1d(List<StepResult> out) {
        return sf.withSession(s -> s.createNativeQuery(
                "SELECT event_id FROM lab_dup_events ORDER BY event_id", Object[].class)
                .setFirstResult(1).setMaxResults(3).getResultList())
                .onItem().transform(rows -> {
                    List<Long> ids = rows.stream().map(r -> ((Number) (r instanceof Object[] arr ? arr[0] : r)).longValue()).toList();
                    out.add(StepResult.pass("B1d", "hr", Map.of("ids", ids, "limitShape", "inlined-or-supported")));
                    return (Void) null;
                })
                .onFailure().recoverWithItem(e -> {
                    out.add(new StepResult("B1d", "hr", true, LabLog.brief((Throwable) e),
                            Map.of("limitShape", "parameterized-limit-rejected")));
                    return (Void) null;
                });
    }

    private Uni<Void> b2(List<StepResult> out) {
        record TypeCase(String name, Object value) {
        }
        List<TypeCase> cases = List.of(
                new TypeCase("String", "公司码测试"),
                new TypeCase("Integer", 42),
                new TypeCase("Long>2^53", 9007199254740993L),
                new TypeCase("Double", 3.14159d),
                new TypeCase("DECIMAL", new BigDecimal("123.456789")),
                new TypeCase("DATETIME", java.time.LocalDateTime.of(2024, 6, 1, 8, 0, 0)),
                new TypeCase("NULL", null),
                new TypeCase("空串", ""));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (TypeCase c : cases) {
            List<Object> params = Arrays.asList(c.value());
            chain = chain.chain(v -> probeHr("SELECT ? AS v", params)
                    .onItem().transform(r -> {
                        data.put("hr-" + c.name(), String.valueOf(r.get("first")) + "/" + r.get("elemType"));
                        return (Void) null;
                    })
                    .onFailure().recoverWithItem(e -> {
                        data.put("hr-" + c.name(), "ERR: " + LabLog.brief((Throwable) e));
                        return (Void) null;
                    }));
        }
        return chain.map(v -> {
            out.add(StepResult.pass("B2-hr", "hr", data));
            return (Void) null;
        });
    }

    private Uni<Void> b4(List<StepResult> out) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (int n : List.of(100, 1000, 5000)) {
            List<Object> ids = new ArrayList<>();
            for (long i = 1; i <= n; i++) {
                ids.add(i);
            }
            StringBuilder ph = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) ph.append(',');
                ph.append('?');
            }
            String sql = "SELECT count(*) AS c FROM lab_dup_events WHERE event_id IN (" + ph + ")";
            chain = chain.chain(v -> sf.withSession(s -> {
                var q = s.createNativeQuery(sql, Long.class);
                for (int i = 0; i < ids.size(); i++) {
                    q = q.setParameter(i + 1, ids.get(i));
                }
                return q.getSingleResult();
            }).onItem().transform(c -> {
                data.put("hr-" + n, c);
                return (Void) null;
            }).onFailure().recoverWithItem(e -> {
                data.put("hr-" + n, "ERR: " + LabLog.brief((Throwable) e));
                return (Void) null;
            }));
        }
        return chain.map(v -> {
            out.add(StepResult.pass("B4-hr", "hr", data));
            return (Void) null;
        });
    }

    private Uni<Void> b5(List<StepResult> out) {
        return add(out, "B5-hr", sf.withSession(s -> s.createNativeQuery(BindingScenarios.COMPLEX_SQL, Object[].class)
                .setParameter(1, 100L).setParameter(2, 200L).getResultList())
                .map(rows -> {
                    List<String> hrRows = rows.stream().map(r -> {
                        Object[] a = (Object[]) r;
                        return a[0] + "#" + a[1] + "#" + new BigDecimal(String.valueOf(a[2])).toPlainString()
                                + "#" + normalizeBool(a[3]);
                    }).toList();
                    return Map.<String, Object>of("rows", hrRows);
                }));
    }

    private Uni<Void> b5b(List<StepResult> out) {
        return add(out, "B5b-hr", probeHr("SELECT array_contains([1,2,3], ?) AS h", List.of(2))
                .map(r -> Map.<String, Object>of("literal", String.valueOf(r.get("first")))));
    }

    // ─── C 组 HR 腿（实体 DATETIME 提取等） ───

    public Uni<List<StepResult>> runCHr() {
        List<StepResult> out = new ArrayList<>();
        // C1-hr：实体 find（含 DATETIME 字段，观察是否触发 Unable to extract JDBC value）
        return sf.withSession(s -> s.find(LabDupEvent.class, 1L))
                .onItem().transform(e -> {
                    out.add(StepResult.pass("C1-hr", "hr", Map.of(
                            "eventTime", String.valueOf(e.eventTime),
                            "payload", String.valueOf(e.payload))));
                    return (Void) null;
                })
                .onFailure().recoverWithItem(e -> {
                    out.add(new StepResult("C1-hr", "hr", true, LabLog.brief((Throwable) e),
                            Map.of("hrDatetimeReadable", false)));
                    return (Void) null;
                })
                .chain(v -> d0Probes(out))
                // 实体形态（find）已被 C1-hr 证伪（DATETIME 列提取炸）；以下用 native 投影验证绕过形态
                .chain(v -> add(out, "C2-hr", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_value FROM lab_dup_events WHERE event_id = 1", BigDecimal.class)
                        .getSingleResult())
                        .map(val -> Map.<String, Object>of("eventValue", String.valueOf(val)))))
                .chain(v -> add(out, "C3-hr", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_id FROM lab_dup_events WHERE event_name = ?", Long.class)
                        .setParameter(1, "bigint-probe").getSingleResult())
                        .map(val -> Map.<String, Object>of("eventId", String.valueOf(val)))))
                .chain(v -> add(out, "C4-hr", sf.withSession(s -> s.createNativeQuery(
                        "SELECT status, remark FROM lab_dup_events WHERE event_id = 3", Object[].class)
                        .getSingleResult())
                        .map(arr -> Map.<String, Object>of("status", String.valueOf(arr[0]),
                                "remark", String.valueOf(arr[1])))))
                .chain(v -> add(out, "C5-hr", sf.withSession(s -> s.createNativeQuery(
                        "SELECT payload, attrs_json FROM lab_dup_events WHERE event_id = 3", Object[].class)
                        .getSingleResult())
                        .map(arr -> Map.<String, Object>of("payload", String.valueOf(normalizeCell(arr[0])),
                                "attrsJson", String.valueOf(normalizeCell(arr[1]))))))
                .chain(v -> add(out, "C6-hr", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_id FROM lab_dup_events WHERE company_id = -999", Object[].class).getResultList())
                        .map(rows -> Map.<String, Object>of("rows", rows.size()))))
                .map(v -> out);
    }

    /** D0 探针：HR 读 Doris DATETIME 的边界定量（native 直读 / CAST / getString） */
    private Uni<Void> d0Probes(List<StepResult> out) {
        return add(out, "D0a", sf.withSession(s -> s.createNativeQuery(
                "SELECT event_time FROM lab_dup_events WHERE event_id = 1", java.time.LocalDateTime.class)
                .getSingleResult())
                .map(val -> {
                    Object n = normalizeCell(val);
                    return Map.<String, Object>of("value", String.valueOf(n),
                            "type", n == null ? "null" : n.getClass().getSimpleName());
                }))
                .chain(v1 -> add(out, "D0b", sf.withSession(s -> s.createNativeQuery(
                        "SELECT CAST(event_time AS CHAR) FROM lab_dup_events WHERE event_id = 1", String.class)
                        .getSingleResult())
                        .map(val -> Map.<String, Object>of("value", String.valueOf(normalizeCell(val))))))
                .chain(v2 -> add(out, "D0c", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_time FROM lab_dup_events WHERE event_id = 1", String.class)
                        .getSingleResult())
                        .map(val -> Map.<String, Object>of("value", String.valueOf(normalizeCell(val))))))
                .chain(v3 -> add(out, "D0d", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_id, event_time, event_value FROM lab_dup_events WHERE event_id = 1", Object[].class)
                        .getSingleResult())
                        .map(arr -> {
                            Object dt = normalizeCell(arr[1]);
                            return Map.<String, Object>of(
                                    "eventId", String.valueOf(arr[0]),
                                    "eventTime", String.valueOf(dt),
                                    "eventTimeType", dt == null ? "null" : dt.getClass().getSimpleName());
                        })));
    }

    // ─── E 组 HR 腿 ───

    public Uni<List<StepResult>> runEHr() {
        List<StepResult> out = new ArrayList<>();
        return add(out, "E1-hr", sf.withSession(s -> s.createNativeQuery(
                "SELECT event_id, event_time, company_id, event_name, event_value, payload, attrs_json, status, remark "
                        + "FROM lab_dup_events WHERE event_id = ?", Object[].class)
                .setParameter(1, 1L).getResultList()
                .chain(rows1 -> s.createNativeQuery(
                        "SELECT event_id, event_time, company_id, event_name, event_value, payload, attrs_json, status, remark "
                                + "FROM lab_dup_events WHERE event_id = ?", Object[].class)
                        .setParameter(1, 3L).getSingleResult().map(r3 -> new Object[][]{rows1.get(0), r3}))
                .chain(p12 -> s.createNativeQuery(
                        "SELECT event_id, event_time, company_id, event_name, event_value, payload, attrs_json, status, remark "
                                + "FROM lab_dup_events WHERE event_id = ?", Object[].class)
                        .setParameter(1, 4L).getSingleResult().map(r4 -> new Object[][]{p12[0], p12[1], r4}))
                .chain(p123 -> s.createNativeQuery(
                        "SELECT event_id, event_time, company_id, event_name, event_value, payload, attrs_json, status, remark "
                                + "FROM lab_dup_events WHERE event_id = ?", Object[].class)
                        .setParameter(1, 9007199254740993L).getSingleResult()
                        .map(rbig -> new Object[][]{p123[0], p123[1], p123[2], rbig})))
                .map(rows -> {
                    Map<String, Object> data = new java.util.LinkedHashMap<>();
                    String[] keys = {"id1", "id3", "id4", "idbig"};
                    for (int i = 0; i < rows.length; i++) {
                        Object[] a = rows[i];
                        Object payload = normalizeCell(a[5]);   // HR native 文本列为 ClobProxy，须解包
                        Object json = normalizeCell(a[6]);
                        String jsonStr = json instanceof io.vertx.core.json.JsonObject jo ? jo.encode() : String.valueOf(json);
                        // DECIMAL scale 口径统一（hr native 保留 wire scale 6，pool/jdbc 为最简形态）
                        Object value = a[4] instanceof BigDecimal bd ? bd.stripTrailingZeros().toPlainString() : a[4];
                        data.put(keys[i], a[0] + "|" + a[1] + "|" + a[2] + "|" + a[3] + "|" + value
                                + "|" + payload + "|" + jsonStr + "|" + a[7] + "|" + a[8]);
                    }
                    return data;
                }))
                .chain(v -> add(out, "E2-hr", sf.withSession(s -> s.createNativeQuery(BindingScenarios.COMPLEX_SQL, Object[].class)
                        .setParameter(1, 100L).setParameter(2, 200L).getResultList())
                        .map(rows -> {
                            List<String> hrRows = rows.stream().map(r -> {
                                Object[] a = (Object[]) r;
                                return a[0] + "#" + a[1] + "#" + new BigDecimal(String.valueOf(a[2])).toPlainString()
                                        + "#" + normalizeBool(a[3]);
                            }).toList();
                            return Map.<String, Object>of("rows", hrRows);
                        })))
                .chain(v -> add(out, "E3-hr", sf.withSession(s -> s.createNativeQuery(
                        "SELECT event_id FROM lab_dup_events WHERE company_id = -1", Object[].class).getResultList())
                        .map(rows -> Map.<String, Object>of("rows", rows.size()))))
                .map(v -> out);
    }
}
