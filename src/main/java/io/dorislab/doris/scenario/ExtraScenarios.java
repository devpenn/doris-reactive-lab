package io.dorislab.doris.scenario;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.reactive.mutiny.Mutiny;

import io.dorislab.doris.hr.LabDupEvent;
import io.dorislab.doris.support.LabLog;
import io.dorislab.doris.support.LabSetup;

import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 场景七（G 组）：其他场景。批量写 preparedBatch、ON DUPLICATE KEY、unique 并发 upsert 竞态、
 * information_schema 参数化、aggregate 模型写语义、emoji 写路径。
 */
@ApplicationScoped
public class ExtraScenarios {

    private final MySQLPool pool;
    private final Mutiny.SessionFactory sf;

    ExtraScenarios(@io.quarkus.reactive.datasource.ReactiveDataSource("dorispool") MySQLPool pool,
                   Mutiny.SessionFactory sf) {
        this.pool = pool;
        this.sf = sf;
    }

    public List<StepResult> runAll() {
        List<StepResult> out = new ArrayList<>();
        out.add(g1PreparedBatch());
        out.add(g2OnDuplicateKey());
        out.add(g3ConcurrentUpsert());
        out.add(g4InformationSchema());
        out.add(g5AggregateWrite());
        out.add(g6EmojiWrite());
        return out;
    }

    private StepResult g1PreparedBatch() {
        String sql = "INSERT INTO lab_dup_events (event_id, event_time, company_id, event_name, event_value, payload, attrs_json, tags, status, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Tuple> batch = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2024, 6, 15, 8, 0, 0);
        for (int i = 1; i <= 5; i++) {
            batch.add(Tuple.from(new Object[]{20000L + i, t.plusMinutes(i), 100L, "batch-" + i,
                    BigDecimal.valueOf(i).movePointLeft(3), "批量-" + i, "{\"b\":" + i + "}", null, 1, "b"}));
        }
        try {
            io.vertx.mutiny.sqlclient.RowSet<Row> rs = pool.preparedQuery(sql).executeBatch(batch)
                    .await().atMost(Duration.ofSeconds(30));
            Long c = pool.preparedQuery("SELECT count(*) AS c FROM lab_dup_events WHERE event_id BETWEEN 20001 AND 20005")
                    .execute().await().atMost(Duration.ofSeconds(20)).iterator().next().getLong("c");
            LabSetup.reseed();
            return new StepResult("G1", "pool", c == 5, c == 5 ? null : "落库行数不符",
                    Map.of("affected", rs.rowCount(), "seededCount", c));
        } catch (Throwable e) {
            LabSetup.reseed();
            return StepResult.broken("G1", "pool", LabLog.brief(e));
        }
    }

    private StepResult g2OnDuplicateKey() {
        String sql = "INSERT INTO lab_uk_users (user_id, user_name) VALUES (92001, 'odku') ON DUPLICATE KEY UPDATE user_name = 'odku2'";
        String err = null;
        try {
            pool.query(sql).execute().await().atMost(Duration.ofSeconds(20));
        } catch (Throwable e) {
            err = LabLog.brief(e);
        }
        return new StepResult("G2", "pool", true, err, Map.of(
                "accepted", err == null, "err", String.valueOf(err)));
    }

    private StepResult g3ConcurrentUpsert() {
        int n = 10;
        String sql = "INSERT INTO lab_uk_users (user_id, user_name, email, balance, status, gmt_create, gmt_modified, ext_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        CountDownLatch latch = new CountDownLatch(n);
        AtomicInteger failures = new AtomicInteger();
        List<String> errs = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.preparedQuery(sql).execute(Tuple.from(new Object[]{9500L, "并发-" + idx, "w" + idx + "@lab.local",
                            BigDecimal.TEN, 1, LocalDateTime.of(2024, 6, 15, 8, 0, 0),
                            LocalDateTime.of(2024, 6, 15, 8, 0, 0), null}))
                    .subscribe().with(
                            rs -> latch.countDown(),
                            e -> {
                                failures.incrementAndGet();
                                errs.add(LabLog.brief(e));
                                latch.countDown();
                            });
        }
        try {
            boolean done = latch.await(30, TimeUnit.SECONDS);
            Row r = pool.preparedQuery("SELECT count(*) AS c, max(user_name) AS winner FROM lab_uk_users WHERE user_id = 9500")
                    .execute().await().atMost(Duration.ofSeconds(20)).iterator().next();
            long rows = r.getLong("c");
            LabSetup.reseed();
            return new StepResult("G3", "pool", done && failures.get() == 0 && rows == 1,
                    failures.get() > 0 ? "并发 upsert 失败: " + errs : (rows != 1 ? "终态行数=" + rows : null),
                    Map.of("concurrent", n, "finalRows", rows, "winner", String.valueOf(r.getString("winner")),
                            "failures", failures.get(), "elapsedMs", System.currentTimeMillis() - t0));
        } catch (Throwable e) {
            LabSetup.reseed();
            return StepResult.broken("G3", "pool", LabLog.brief(e));
        }
    }

    private StepResult g4InformationSchema() {
        try {
            List<String> cols = pool.preparedQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = ? LIMIT 0, 20")
                    .execute(Tuple.of("lab_dup_events")).await().atMost(Duration.ofSeconds(20))
                    .stream().map(r -> r.getString(0)).toList();
            return new StepResult("G4", "pool", cols.contains("event_id"),
                    cols.contains("event_id") ? null : "列缺失: " + cols, Map.of("cols", cols));
        } catch (Throwable e) {
            return StepResult.broken("G4", "pool", LabLog.brief(e));
        }
    }

    private StepResult g5AggregateWrite() {
        String sql = "INSERT INTO lab_agg_metrics (biz_date, company_id, metric_name, total_value, last_value, max_value) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try {
            pool.preparedQuery(sql).execute(Tuple.of(LocalDate.of(2024, 6, 2), 300L, "msgs", 13L, 13L, 13L))
                    .await().atMost(Duration.ofSeconds(20));
            Row r = pool.preparedQuery("SELECT total_value, last_value, max_value FROM lab_agg_metrics "
                    + "WHERE biz_date = '2024-06-02' AND company_id = 300 AND metric_name = 'msgs'")
                    .execute().await().atMost(Duration.ofSeconds(20)).iterator().next();
            LabSetup.reseed();
            return new StepResult("G5", "pool", true, null, Map.of(
                    "total", r.getLong("total_value"), "last", r.getLong("last_value"), "max", r.getLong("max_value"),
                    "expectedTotal", 14L, "expectedLast", 13L, "expectedMax", 13L));
        } catch (Throwable e) {
            LabSetup.reseed();
            return StepResult.broken("G5", "pool", LabLog.brief(e));
        }
    }

    private StepResult g6EmojiWrite() {
        String name = "写入-😀-🎉-中文";
        String sql = "INSERT INTO lab_uk_users (user_id, user_name, email, balance, status, gmt_create, gmt_modified, ext_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            pool.preparedQuery(sql).execute(Tuple.from(new Object[]{9600L, name, null, null, null,
                            LocalDateTime.of(2024, 6, 15, 8, 0, 0), null, null}))
                    .await().atMost(Duration.ofSeconds(20));
            String hrName = sf.withSession(s -> s.createNativeQuery(
                    "SELECT user_name FROM lab_uk_users WHERE user_id = 9600", String.class).getSingleResult())
                    .await().atMost(Duration.ofSeconds(20));
            pool.query("DELETE FROM lab_uk_users WHERE user_id = 9600").execute().await().atMost(Duration.ofSeconds(20));
            return new StepResult("G6", "all", name.equals(hrName),
                    name.equals(hrName) ? null : "读回不一致: " + hrName, Map.of("written", name, "readBack", hrName));
        } catch (Throwable e) {
            return StepResult.broken("G6", "all", LabLog.brief(e));
        }
    }
}
