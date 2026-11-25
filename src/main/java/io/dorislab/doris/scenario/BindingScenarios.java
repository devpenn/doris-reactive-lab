package io.dorislab.doris.scenario;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import io.dorislab.doris.support.LabLog;
import io.dorislab.doris.support.LabSetup;

import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 场景二（B 组，pool 腿）：MySQLPool preparedQuery 探针。
 * 「预期失败」探针按 ok=true+err 记录原始报错；hr 腿见 {@link HrLegScenarios}（eventloop 限定）。
 */
@ApplicationScoped
public class BindingScenarios {

    private final MySQLPool pool;

    BindingScenarios(@io.quarkus.reactive.datasource.ReactiveDataSource("dorispool") MySQLPool pool) {
        this.pool = pool;
    }

    /** 场景公共 SQL（B5/E2 复用）：GROUP BY+JOIN+IN+array_contains（聚合列包 max 规避 GROUP BY 语义校验） */
    public static final String COMPLEX_SQL = """
            SELECT e.company_id AS cid, count(*) AS cnt, sum(e.event_value) AS total, max(array_contains(e.tags, 3)) AS has3
            FROM lab_dup_events e JOIN lab_uk_users u ON e.company_id = u.user_id
            WHERE e.company_id IN (?, ?)
            GROUP BY e.company_id ORDER BY e.company_id
            LIMIT 0, 10
            """;

    public List<StepResult> runAll() {
        List<StepResult> out = new ArrayList<>();
        out.add(b1LimitPlaceholder());
        out.add(b1cLimitLiteralWorkaround());
        out.add(b2TypeBindings());
        out.add(b3LikeParam());
        out.add(b4LargeInList());
        out.add(b4bInlineInWorkaround());
        out.add(b5ComplexAggregate());
        out.add(b5bArrayContains());
        return out;
    }

    /** 探针：执行 SQL，返回 ok/err/data 三元组（不抛异常） */
    private record Probe(boolean ok, String err, Map<String, Object> data) {
    }

    private Probe probePool(String sql, Tuple params, java.util.function.Function<Row, Map<String, Object>> reader) {
        try {
            RowSet<Row> rs = pool.preparedQuery(sql).execute(params).await().atMost(Duration.ofSeconds(60));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rows", rs.size());
            if (reader != null && rs.iterator().hasNext()) {
                data.putAll(reader.apply(rs.iterator().next()));
            }
            return new Probe(true, null, data);
        } catch (Throwable e) {
            return new Probe(false, LabLog.brief(e), Map.of());
        }
    }

    private StepResult b1LimitPlaceholder() {
        Probe p = probePool("SELECT 1 AS x LIMIT ?, ?", Tuple.of(0L, 1), null);
        boolean rejected = !p.ok() && p.err() != null && p.err().contains("mismatched input");
        boolean passed = p.ok() && Integer.valueOf(1).equals(p.data().get("rows"));
        return new StepResult("B1", "pool", rejected || passed, rejected || passed ? null : "形态异常",
                Map.of("poolOk", p.ok(), "poolErr", String.valueOf(p.err())));
    }

    private StepResult b1cLimitLiteralWorkaround() {
        String sql = "SELECT event_id FROM lab_dup_events WHERE company_id = ? ORDER BY event_id LIMIT 3 OFFSET 1";
        List<Long> poolIds = new ArrayList<>();
        try {
            pool.preparedQuery(sql).execute(Tuple.of(100L)).await().atMost(Duration.ofSeconds(30))
                    .forEach(r -> poolIds.add(r.getLong("event_id")));
        } catch (Throwable e) {
            return StepResult.broken("B1c", "pool", LabLog.brief(e));
        }
        return new StepResult("B1c", "pool", true, null, Map.of("poolIds", poolIds));
    }

    private StepResult b2TypeBindings() {
        record TypeCase(String name, Object value) {
        }
        List<TypeCase> cases = List.of(
                new TypeCase("String", "公司码测试"),
                new TypeCase("Integer", 42),
                new TypeCase("Long>2^53", 9007199254740993L),
                new TypeCase("Double", 3.14159d),
                new TypeCase("DECIMAL", new BigDecimal("123.456789")),
                new TypeCase("DATETIME", LocalDateTime.of(2024, 6, 1, 8, 0, 0)),
                new TypeCase("NULL", null),
                new TypeCase("空串", ""));
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> anomalies = new ArrayList<>();
        for (TypeCase c : cases) {
            // mutiny Tuple 无 addNull；Tuple.from(Object[]) 允许 null（G3/G6 已实证）
            Tuple t = Tuple.from(new Object[]{c.value()});
            Probe p = probePool("SELECT ? AS v", t, r -> Map.of("got", readTyped(r, c.value())));
            data.put("pool-" + c.name(), p.ok() ? String.valueOf(p.data().get("got")) : "ERR: " + p.err());
            if (!p.ok()) {
                anomalies.add("pool-" + c.name());
            }
        }
        return new StepResult("B2", "pool", anomalies.isEmpty(), anomalies.isEmpty() ? null : "异常项: " + anomalies, data);
    }

    private static Object readTyped(Row r, Object expected) {
        if (expected == null) {
            return r.getInteger("v");
        }
        try {
            if (expected instanceof String) return r.getString("v");
            if (expected instanceof Integer) return r.getInteger("v");
            if (expected instanceof Long) return r.getLong("v");
            if (expected instanceof Double) return r.getDouble("v");
            if (expected instanceof BigDecimal) return r.getBigDecimal("v");
            if (expected instanceof LocalDateTime) return r.getLocalDateTime("v");
            return r.get(expected.getClass(), "v");
        } catch (ClassCastException e) {
            // FE 对 DECIMAL 等参数的 prepare 回显类型可能漂移（如 String），降级取原值并标注类型
            Object raw = r.getValue("v");
            return String.valueOf(raw) + "<" + (raw == null ? "null" : raw.getClass().getSimpleName()) + ">";
        }
    }

    private StepResult b3LikeParam() {
        // 先重置种子，保证 LIKE 语义判定不受前序场景污染
        LabSetup.reseed();
        List<String> matched = new ArrayList<>();
        try {
            pool.preparedQuery("SELECT event_id, event_name FROM lab_dup_events WHERE event_name LIKE CONCAT('%', ?, '%') ORDER BY event_id")
                    .execute(Tuple.of("log")).await().atMost(Duration.ofSeconds(30))
                    .forEach(r -> matched.add(r.getLong("event_id") + ":" + r.getString("event_name")));
            Long cnt = pool.preparedQuery("SELECT count(*) AS c FROM lab_dup_events WHERE event_name LIKE CONCAT('%', ?, '%')")
                    .execute(Tuple.of("log")).await().atMost(Duration.ofSeconds(30))
                    .iterator().next().getLong("c");
            return new StepResult("B3", "pool", true, null, Map.of("count", cnt, "matched", matched));
        } catch (Throwable e) {
            return StepResult.broken("B3", "pool", LabLog.brief(e));
        }
    }

    private StepResult b4LargeInList() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> anomalies = new ArrayList<>();
        for (int n : new int[]{100, 1000, 5000}) {
            List<Object> ids = LongStream.rangeClosed(1, n).boxed().collect(Collectors.toList());
            String sql = "SELECT count(*) AS c FROM lab_dup_events WHERE event_id IN (" + placeholders(n) + ")";
            try {
                Long c = pool.preparedQuery(sql).execute(Tuple.from(ids))
                        .await().atMost(Duration.ofSeconds(60)).iterator().next().getLong("c");
                data.put("pool-" + n, c);
            } catch (Throwable e) {
                data.put("pool-" + n, "ERR: " + LabLog.brief(e));
                anomalies.add("pool-" + n);
            }
        }
        return new StepResult("B4", "pool", anomalies.isEmpty(), anomalies.isEmpty() ? null : "异常项: " + anomalies, data);
    }

    private StepResult b4bInlineInWorkaround() {
        String inClause = LongStream.rangeClosed(1, 5000).mapToObj(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT count(*) AS c FROM lab_dup_events WHERE event_id IN (" + inClause + ")";
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.put("pool", pool.query(sql).execute().await().atMost(Duration.ofSeconds(60))
                    .iterator().next().getLong("c"));
        } catch (Throwable e) {
            data.put("pool", "ERR: " + LabLog.brief(e));
        }
        return new StepResult("B4b", "pool", true, null, data);
    }

    private StepResult b5ComplexAggregate() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            List<String> poolRows = new ArrayList<>();
            pool.preparedQuery(COMPLEX_SQL).execute(Tuple.of(100L, 200L))
                    .await().atMost(Duration.ofSeconds(30))
                    .forEach(r -> poolRows.add(r.getLong("cid") + "#" + r.getLong("cnt") + "#" + r.getBigDecimal("total").toPlainString() + "#" + r.getBoolean("has3")));
            data.put("pool", poolRows);
        } catch (Throwable e) {
            data.put("pool", "ERR: " + LabLog.brief(e));
        }
        return new StepResult("B5", "pool", true, null, data);
    }

    private StepResult b5bArrayContains() {
        Map<String, Object> data = new LinkedHashMap<>();
        Probe litP = probePool("SELECT array_contains([1,2,3], ?) AS h", Tuple.of(2),
                r -> Map.of("h", String.valueOf(r.getValue("h"))));
        data.put("literal-pool", litP.ok() ? String.valueOf(litP.data().get("h")) : "ERR: " + litP.err());
        try {
            List<Long> ids = pool.preparedQuery("SELECT event_id FROM lab_dup_events WHERE array_contains(tags, ?) ORDER BY event_id LIMIT 0, 5")
                    .execute(Tuple.of(3)).await().atMost(Duration.ofSeconds(30))
                    .stream().map(r -> r.getLong("event_id")).toList();
            data.put("tagsColumn", ids);
        } catch (Throwable e) {
            data.put("tagsColumn", "ERR: " + LabLog.brief(e));
        }
        return new StepResult("B5b", "pool", true, null, data);
    }

    static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }
}
