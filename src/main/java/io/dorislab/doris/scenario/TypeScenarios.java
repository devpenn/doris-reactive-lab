package io.dorislab.doris.scenario;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import io.dorislab.doris.support.LabEnv;
import io.dorislab.doris.support.LabLog;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 场景三（C 组）：数据类型与行映射。DATETIME 时区（三路线 + Connector/J 参数矩阵）、
 * DECIMAL 精度、BIGINT>2^53、NULL、TEXT/JSON 列（vertx 解码为 JsonObject）、空结果集。
 */
@ApplicationScoped
public class TypeScenarios {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private final MySQLPool pool;
    private final AgroalDataSource jdbc;

    TypeScenarios(@io.quarkus.reactive.datasource.ReactiveDataSource("dorispool") MySQLPool pool,
                  @DataSource("dorisjdbc") AgroalDataSource jdbc) {
        this.pool = pool;
        this.jdbc = jdbc;
    }

    public List<StepResult> runAll() {
        List<StepResult> out = new ArrayList<>();
        out.add(c0VertxTimeZoneSurface());
        out.add(c1Datetime());
        out.add(c1bJdbcTimezoneParamMatrix());
        out.add(c2Decimal());
        out.add(c3Bigint());
        out.add(c4Null());
        out.add(c5TextJson());
        out.add(c6Empty());
        return out;
    }

    private StepResult c0VertxTimeZoneSurface() {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.Method m : io.vertx.mysqlclient.MySQLConnectOptions.class.getMethods()) {
            if (m.getName().startsWith("set")) {
                names.add(m.getName());
            }
        }
        boolean hasTz = names.stream().anyMatch(s -> s.toLowerCase().contains("timezone"));
        return StepResult.info("C0", "pool", Map.of("timeZoneOptionExists", hasTz, "setterCount", names.size()));
    }

    private StepResult c1Datetime() {
        try {
            LocalDateTime fromPool = pool.preparedQuery("SELECT event_time FROM lab_dup_events WHERE event_id = ?")
                    .execute(Tuple.of(1L)).await().atMost(Duration.ofSeconds(20))
                    .iterator().next().getLocalDateTime("event_time");
            LocalDateTime fromJdbc;
            long jdbcEpoch;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT event_time FROM lab_dup_events WHERE event_id = ?")) {
                ps.setLong(1, 1L);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    fromJdbc = rs.getObject("event_time", LocalDateTime.class);
                    Timestamp ts = rs.getTimestamp("event_time");
                    jdbcEpoch = ts.getTime();
                }
            }
            long poolEpoch = fromPool.atZone(SH).toInstant().toEpochMilli();
            return new StepResult("C1", "pool+jdbc", true, null, Map.of(
                    "pool", String.valueOf(fromPool),
                    "jdbc", String.valueOf(fromJdbc),
                    "poolEpochMs", poolEpoch,
                    "jdbcEpochMs", jdbcEpoch,
                    "epochEqual", poolEpoch == jdbcEpoch,
                    "allEqual", fromPool.equals(fromJdbc)));
        } catch (Throwable e) {
            return StepResult.broken("C1", "all", LabLog.brief(e));
        }
    }

    private StepResult c1bJdbcTimezoneParamMatrix() {
        record ParamCase(String label, String params) {
        }
        List<ParamCase> cases = List.of(
                new ParamCase("serverTimezone=Asia/Shanghai(现网基线)", "useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai"),
                new ParamCase("connectionTimeZone=Asia/Shanghai(新参数)", "useUnicode=true&characterEncoding=utf-8&connectionTimeZone=Asia/Shanghai"),
                new ParamCase("connectionTimeZone=UTC", "useUnicode=true&characterEncoding=utf-8&connectionTimeZone=UTC"),
                new ParamCase("connectionTimeZone=UTC+forceConnectionTimeZoneToSession=true", "useUnicode=true&characterEncoding=utf-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
        Map<String, Object> data = new LinkedHashMap<>();
        for (ParamCase pc : cases) {
            try (Connection c = DriverManager.getConnection(LabEnv.jdbcUrl(pc.params()), LabEnv.user(), LabEnv.password());
                 Statement st = c.createStatement()) {
                st.execute("SET SESSION query_timeout = 20");
                try (PreparedStatement ps = c.prepareStatement("SELECT event_time FROM lab_dup_events WHERE event_id = 1");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    LocalDateTime ldt = rs.getObject(1, LocalDateTime.class);
                    long epoch = rs.getTimestamp(1).getTime();
                    data.put(pc.label(), ldt + " | epochMs=" + epoch);
                }
            } catch (Throwable e) {
                data.put(pc.label(), "ERR: " + LabLog.brief(e));
            }
        }
        return new StepResult("C1b", "jdbc", true, null, data);
    }

    private StepResult c2Decimal() {
        try {
            BigDecimal fromPool = pool.preparedQuery("SELECT event_value FROM lab_dup_events WHERE event_id = ?")
                    .execute(Tuple.of(1L)).await().atMost(Duration.ofSeconds(20))
                    .iterator().next().getBigDecimal("event_value");
            BigDecimal fromJdbc;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT event_value FROM lab_dup_events WHERE event_id = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                fromJdbc = rs.getBigDecimal("event_value");
            }
            return new StepResult("C2", "pool+jdbc", true, null, Map.of(
                    "pool", fromPool.toPlainString(), "jdbc", fromJdbc.toPlainString(),
                    "scale", fromPool.scale(), "allEqual", fromPool.equals(fromJdbc)));
        } catch (Throwable e) {
            return StepResult.broken("C2", "all", LabLog.brief(e));
        }
    }

    private StepResult c3Bigint() {
        long expected = 9007199254740993L;
        try {
            Long fromPool = pool.preparedQuery("SELECT event_id FROM lab_dup_events WHERE event_name = ?")
                    .execute(Tuple.of("bigint-probe")).await().atMost(Duration.ofSeconds(20))
                    .iterator().next().getLong("event_id");
            Long fromJdbc;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT event_id FROM lab_dup_events WHERE event_name = ?")) {
                ps.setString(1, "bigint-probe");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    fromJdbc = rs.getLong("event_id");
                }
            }
            return new StepResult("C3", "pool+jdbc", true, null, Map.of(
                    "expected", expected, "pool", fromPool, "jdbc", fromJdbc,
                    "allExact", expected == fromPool && expected == fromJdbc));
        } catch (Throwable e) {
            return StepResult.broken("C3", "all", LabLog.brief(e));
        }
    }

    private StepResult c4Null() {
        try {
            Row r = pool.preparedQuery("SELECT status, remark FROM lab_dup_events WHERE event_id = ?")
                    .execute(Tuple.of(3L)).await().atMost(Duration.ofSeconds(20)).iterator().next();
            Boolean jdbcNull;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT status, remark FROM lab_dup_events WHERE event_id = 3");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                jdbcNull = rs.getObject("status") == null;
            }
            Map<String, Object> c4 = new LinkedHashMap<>();
            c4.put("poolStatus", r.getInteger("status"));
            c4.put("poolRemark", String.valueOf(r.getString("remark")));
            c4.put("jdbcStatusNull", jdbcNull);
            return new StepResult("C4", "pool+jdbc", true, null, c4);
        } catch (Throwable e) {
            return StepResult.broken("C4", "all", LabLog.brief(e));
        }
    }

    private StepResult c5TextJson() {
        try {
            Row r = pool.preparedQuery("SELECT payload, attrs_json FROM lab_dup_events WHERE event_id = ?")
                    .execute(Tuple.of(3L)).await().atMost(Duration.ofSeconds(20)).iterator().next();
            String payload = r.getString("payload");
            // vertx 把 JSON 列解码为 JsonObject（getString 抛 CCE，首轮实跑实证）
            Object raw = r.getValue("attrs_json");
            String jsonPool = raw instanceof io.vertx.core.json.JsonObject jo ? jo.encode() : String.valueOf(raw);
            String jsonJdbc;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT payload, attrs_json FROM lab_dup_events WHERE event_id = 3");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                jsonJdbc = rs.getString("attrs_json");
            }
            return new StepResult("C5", "pool+jdbc", true, null, Map.of(
                    "poolPayload", payload,
                    "poolJson", jsonPool, "jdbcJson", jsonJdbc,
                    "rawType", raw == null ? "null" : raw.getClass().getSimpleName(),
                    "jsonAllEqual", jsonPool.equals(jsonJdbc)));
        } catch (Throwable e) {
            return StepResult.broken("C5", "all", LabLog.brief(e));
        }
    }

    private StepResult c6Empty() {
        try {
            int poolSize = pool.preparedQuery("SELECT event_id FROM lab_dup_events WHERE company_id = ?")
                    .execute(Tuple.of(-999L)).await().atMost(Duration.ofSeconds(20)).size();
            boolean jdbcEmpty;
            try (Connection c = jdbc.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT event_id FROM lab_dup_events WHERE company_id = -999");
                 ResultSet rs = ps.executeQuery()) {
                jdbcEmpty = !rs.next();
            }
            return new StepResult("C6", "pool+jdbc", true, null, Map.of(
                    "poolRows", poolSize, "jdbcEmpty", jdbcEmpty));
        } catch (Throwable e) {
            return StepResult.broken("C6", "all", LabLog.brief(e));
        }
    }
}
