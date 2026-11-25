package io.dorislab.doris.scenario;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import io.dorislab.doris.support.LabLog;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 场景五（E 组）：语义对照。同一 SQL 同一参数，MySQLPool vs Hibernate Reactive vs 现网 JDBC(Agroal)
 * 三方结果逐字段一致（规范字符串化后交 e2e 层比对）。
 */
@ApplicationScoped
public class CompareScenarios {

    static final String ROW_SQL = """
            SELECT event_id, event_time, company_id, event_name, event_value, payload, attrs_json, status, remark
            FROM lab_dup_events WHERE event_id = ?
            """;

    private final MySQLPool pool;
    private final AgroalDataSource jdbc;

    CompareScenarios(@io.quarkus.reactive.datasource.ReactiveDataSource("dorispool") MySQLPool pool,
                     @DataSource("dorisjdbc") AgroalDataSource jdbc) {
        this.pool = pool;
        this.jdbc = jdbc;
    }

    public List<StepResult> runAll() {
        List<StepResult> out = new ArrayList<>();
        out.add(e1RowEquality());
        out.add(e2AggregateEquality());
        out.add(e3EmptyEquality());
        return out;
    }

    private record Rec(Long eventId, LocalDateTime eventTime, Long companyId, String eventName,
                       BigDecimal eventValue, String payload, String attrsJson, Integer status, String remark) {
        String canon() {
            return eventId + "|" + eventTime + "|" + companyId + "|" + eventName + "|" + eventValue
                    + "|" + payload + "|" + attrsJson + "|" + status + "|" + remark;
        }
    }

    private StepResult e1RowEquality() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            for (long id : new long[]{1L, 3L, 4L, 9007199254740993L}) {
                Rec fromPool = readPool(id);
                Rec fromJdbc = readJdbc(id);
                String key = "id" + (id > 1_000_000 ? "big" : id);
                data.put(key + "-pool", fromPool.canon());
                data.put(key + "-jdbc", fromJdbc.canon());
            }
            return new StepResult("E1", "all", true, null, data);
        } catch (Throwable e) {
            return StepResult.broken("E1", "all", LabLog.brief(e));
        }
    }

    private Rec readPool(long id) {
        Row r = pool.preparedQuery(ROW_SQL).execute(Tuple.of(id))
                .await().atMost(java.time.Duration.ofSeconds(20)).iterator().next();
        Object rawJson = r.getValue("attrs_json");
        String attrs = rawJson instanceof io.vertx.core.json.JsonObject jo ? jo.encode() : String.valueOf(rawJson);
        return new Rec(r.getLong("event_id"), r.getLocalDateTime("event_time"), r.getLong("company_id"),
                r.getString("event_name"), r.getBigDecimal("event_value"), r.getString("payload"),
                attrs, r.getInteger("status"), r.getString("remark"));
    }

    private Rec readJdbc(long id) throws Exception {
        try (Connection c = jdbc.getConnection();
             PreparedStatement ps = c.prepareStatement(ROW_SQL)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Rec(rs.getLong("event_id"),
                        rs.getObject("event_time", LocalDateTime.class),
                        rs.getLong("company_id"), rs.getString("event_name"),
                        rs.getBigDecimal("event_value"), rs.getString("payload"),
                        rs.getString("attrs_json"),
                        rs.getObject("status") == null ? null : rs.getInt("status"),
                        rs.getString("remark"));
            }
        }
    }

    private StepResult e2AggregateEquality() {
        String sql = BindingScenarios.COMPLEX_SQL;
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            List<String> fromPool = new ArrayList<>();
            pool.preparedQuery(sql).execute(Tuple.of(100L, 200L))
                    .await().atMost(java.time.Duration.ofSeconds(30))
                    .forEach(r -> fromPool.add(r.getLong("cid") + "#" + r.getLong("cnt") + "#"
                            + r.getBigDecimal("total").toPlainString() + "#" + r.getBoolean("has3")));
            data.put("pool", fromPool);
        } catch (Throwable e) {
            data.put("pool", "ERR: " + LabLog.brief(e));
        }
        // hr 腿移至 HrLegScenarios（eventloop），本方法只做 pool+jdbc
        try {
            List<String> fromJdbc = new ArrayList<>();
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, 100L);
                ps.setLong(2, 200L);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fromJdbc.add(rs.getLong(1) + "#" + rs.getLong(2) + "#" + rs.getBigDecimal(3).toPlainString() + "#" + rs.getBoolean(4));
                    }
                }
            }
            data.put("jdbc", fromJdbc);
        } catch (Throwable e) {
            data.put("jdbc", "ERR: " + LabLog.brief(e));
        }
        return new StepResult("E2", "all", true, null, data);
    }

    private StepResult e3EmptyEquality() {
        try {
            int poolRows = pool.preparedQuery("SELECT event_id FROM lab_dup_events WHERE company_id = ?")
                    .execute(Tuple.of(-1L)).await().atMost(java.time.Duration.ofSeconds(20)).size();
            int jdbcRows = 0;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT event_id FROM lab_dup_events WHERE company_id = -1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    jdbcRows++;
                }
            }
            return new StepResult("E3", "pool+jdbc", true, null,
                    Map.of("poolRows", poolRows, "jdbcRows", jdbcRows));
        } catch (Throwable e) {
            return StepResult.broken("E3", "all", LabLog.brief(e));
        }
    }
}
