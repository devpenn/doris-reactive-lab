package io.dorislab.doris.scenario;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.dorislab.doris.support.LabLog;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 场景六（F 组）：慢查与超时。FE 侧取消（SET_VAR hint / session 变量）、客户端取消是否传导 FE、
 * 超时后连接归还池、JDBC setQueryTimeout 对照（Connector/J KILL QUERY 传导）。
 */
@ApplicationScoped
public class TimeoutScenarios {

    private final MySQLPool pool;
    private final AgroalDataSource jdbc;

    TimeoutScenarios(@io.quarkus.reactive.datasource.ReactiveDataSource("dorispool") MySQLPool pool,
                     @DataSource("dorisjdbc") AgroalDataSource jdbc) {
        this.pool = pool;
        this.jdbc = jdbc;
    }

    public List<StepResult> runAll() {
        List<StepResult> out = new ArrayList<>();
        out.add(f1SetVarHint());
        out.add(f2SessionVariable());
        out.add(f3ClientSideCancel());
        out.add(f4JdbcQueryTimeout());
        out.add(f5ShowProcesslistViaPrepare());
        return out;
    }

    private StepResult f1SetVarHint() {
        String sql = "SELECT /*+ SET_VAR(query_timeout = 1) */ sleep(5) AS s";
        long t0 = System.currentTimeMillis();
        String err = null;
        try {
            pool.preparedQuery(sql).execute().await().atMost(Duration.ofSeconds(30));
        } catch (Throwable e) {
            err = LabLog.brief(e);
        }
        long ms = System.currentTimeMillis() - t0;
        // 连接是否干净归还池
        Integer x = null;
        String afterErr = null;
        try {
            x = pool.preparedQuery("SELECT 1 AS x").execute()
                    .map(rs -> rs.iterator().next().getInteger("x")).await().atMost(Duration.ofSeconds(10));
        } catch (Throwable e) {
            afterErr = LabLog.brief(e);
        }
        return new StepResult("F1", "pool", err != null && Integer.valueOf(1).equals(x), null, Map.of(
                "cancelled", err != null, "elapsedMs", ms, "err", String.valueOf(err),
                "poolCleanAfter", Integer.valueOf(1).equals(x), "afterErr", String.valueOf(afterErr)));
    }

    private StepResult f2SessionVariable() {
        SqlConnection conn = null;
        try {
            conn = pool.getConnection().await().atMost(Duration.ofSeconds(10));
            conn.query("SET SESSION query_timeout = 1").execute().await().atMost(Duration.ofSeconds(10));
            long t0 = System.currentTimeMillis();
            String err = null;
            try {
                conn.query("SELECT sleep(5) AS s").execute().await().atMost(Duration.ofSeconds(30));
            } catch (Throwable e) {
                err = LabLog.brief(e);
            }
            long ms = System.currentTimeMillis() - t0;
            return new StepResult("F2", "pool", err != null, null, Map.of(
                    "cancelled", err != null, "elapsedMs", ms, "err", String.valueOf(err)));
        } catch (Throwable e) {
            return StepResult.broken("F2", "pool", LabLog.brief(e));
        } finally {
            if (conn != null) {
                conn.close().subscribeAsCompletionStage();
            }
        }
    }

    private StepResult f3ClientSideCancel() {
        // 设计：把 dorispool（max-size=8）的 8 条连接全部用「2s 本地超时的 sleep(6)」占满后立即发一个普通查询：
        // 若客户端取消不传导 FE（连接仍被服务端占用 6s），普通查询须排队 ~4s 后才成功；
        // 若取消传导（连接释放），普通查询立即返回。计时差即传导判据（processlist 对 prepare 路径查询 Info 不可见，不能用）。
        long t0 = System.currentTimeMillis();
        String err = null;
        try {
            pool.preparedQuery("SELECT sleep(6) AS s").execute()
                    .ifNoItem().after(Duration.ofSeconds(2)).fail()
                    .await().atMost(Duration.ofSeconds(30));
        } catch (Throwable e) {
            err = LabLog.brief(e);
        }
        long localMs = System.currentTimeMillis() - t0;
        for (int i = 1; i < 8; i++) { // 再占满剩余 7 条连接
            pool.preparedQuery("SELECT sleep(6) AS s").execute()
                    .ifNoItem().after(Duration.ofSeconds(2)).fail()
                    .subscribeAsCompletionStage();
        }
        // 立即用普通查询探测池可用性（计时）
        long p0 = System.currentTimeMillis();
        Integer x = null;
        try {
            x = pool.preparedQuery("SELECT 1 AS x").execute()
                    .map(rs -> rs.iterator().next().getInteger("x")).await().atMost(Duration.ofSeconds(20));
        } catch (Throwable e) {
            // 记录
        }
        long probeWaitMs = System.currentTimeMillis() - p0;
        int queryRowsNow = countRunningQueryRows();
        return new StepResult("F3", "pool", err != null && Integer.valueOf(1).equals(x), null, Map.of(
                "localTimeoutFired", err != null, "localMs", localMs,
                "probeWaitMs", probeWaitMs,
                "propagated", probeWaitMs < 1500,
                "poolUsable", Integer.valueOf(1).equals(x),
                "runningQueryRows", queryRowsNow,
                "note", "probeWait>~3s=取消未传导（连接被 sleep 占满）；<1.5s=已传导或连接复用正常"));
    }

    /** text 协议（JDBC）专用：Info 可见，按 Info 含 sleep(5) 精确计数（避开 F3 的 sleep(6) 残留） */
    private int countSleepInfoRows() {
        try {
            int n = 0;
            for (Row r : pool.query("SHOW PROCESSLIST").execute().await().atMost(Duration.ofSeconds(10))) {
                Object info = r.getValue("Info");
                Object command = r.getValue("Command");
                if (info != null && String.valueOf(info).contains("sleep(5)") && !"Sleep".equals(String.valueOf(command))) {
                    n++;
                }
            }
            return n;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 当前 FE 上 Command=Query 的行数（prepare 路径查询 Info 不可见，只能按 Command 计数，且含本探测自身） */
    private int countRunningQueryRows() {
        try {
            int n = 0;
            for (Row r : pool.query("SHOW PROCESSLIST").execute().await().atMost(Duration.ofSeconds(10))) {
                if ("Query".equals(String.valueOf(r.getValue("Command")))) {
                    n++;
                }
            }
            return n;
        } catch (Throwable e) {
            return -1;
        }
    }

    private StepResult f4JdbcQueryTimeout() {
        try (Connection c = jdbc.getConnection()) {
            long t0 = System.currentTimeMillis();
            String err = null;
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(1);
                st.execute("SELECT sleep(5) AS s");
            } catch (Throwable e) {
                err = LabLog.brief(e);
            }
            long ms = System.currentTimeMillis() - t0;
            boolean sameConnUsable = false;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1 AS x")) {
                rs.next();
                sameConnUsable = rs.getInt("x") == 1;
            }
            int lingering = countSleepInfoRows();
            return new StepResult("F4", "jdbc", err != null && sameConnUsable, null, Map.of(
                    "cancelled", err != null, "elapsedMs", ms, "err", String.valueOf(err),
                    "sameConnUsable", sameConnUsable,
                    "lingeringAfter", lingering, "propagated", lingering == 0));
        } catch (Throwable e) {
            return StepResult.broken("F4", "jdbc", LabLog.brief(e));
        }
    }

    private StepResult f5ShowProcesslistViaPrepare() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            Uni<io.vertx.mutiny.sqlclient.RowSet<Row>> u = pool.preparedQuery("SHOW PROCESSLIST").execute();
            for (Row r : u.await().atMost(Duration.ofSeconds(10))) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String col : List.of("Id", "User", "Command", "Time", "Info")) {
                    Object v = r.getValue(col);
                    m.put(col, v == null ? null : String.valueOf(v));
                }
                rows.add(m);
            }
            return new StepResult("F5", "pool", true, null, Map.of("rows", rows.size(), "sample", rows.isEmpty() ? "" : String.valueOf(rows.get(0))));
        } catch (Throwable e) {
            return StepResult.broken("F5", "pool", LabLog.brief(e));
        }
    }
}
