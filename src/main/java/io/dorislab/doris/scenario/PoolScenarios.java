package io.dorislab.doris.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.dorislab.doris.support.LabEnv;
import io.dorislab.doris.support.LabLog;

import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 场景一（A 组，health 部分 A6-A9 由 e2e 层打 /q/health 端点 + run-e2e.sh boot 变体覆盖）：
 * 建池与首连耗时 / max-size 并发排队 / 池耗尽行为 / kill 断线自愈 / 配置路径建池。
 * 全部用程序化池做破坏性实验，与应用管理的 dorispool 隔离。运行在 @Blocking worker 线程。
 */
@ApplicationScoped
public class PoolScenarios {

    public List<StepResult> runAll() {
        List<StepResult> out = new ArrayList<>();
        out.add(a1());
        out.add(a2());
        out.add(a3());
        out.add(a4());
        out.add(a5());
        return out;
    }

    private StepResult a1() {
        long t0 = System.nanoTime();
        MySQLPool pool = LabEnv.newPool(4, 0);
        try {
            RowSet<Row> rs = pool.preparedQuery("SELECT 1 AS x").execute()
                    .await().atMost(Duration.ofSeconds(20));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = rs.iterator().next().getInteger("x") == 1;
            return new StepResult("A1", "pool", ok, null, Map.of("firstConnMs", ms));
        } catch (Throwable e) {
            return StepResult.broken("A1", "pool", LabLog.brief(e));
        } finally {
            pool.close();
        }
    }

    private StepResult a2() {
        int maxSize = 2;
        int total = 6;
        MySQLPool pool = LabEnv.newPool(maxSize, 0);
        try {
            pool.preparedQuery("SELECT 1").execute().await().atMost(Duration.ofSeconds(10));
            long t = System.nanoTime();
            pool.preparedQuery("SELECT 1 AS x").execute().await().atMost(Duration.ofSeconds(10));
            long singleMs = (System.nanoTime() - t) / 1_000_000;

            List<Long> completions = new CopyOnWriteArrayList<>();
            AtomicInteger failures = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(total);
            long t0 = System.nanoTime();
            for (int i = 0; i < total; i++) {
                pool.preparedQuery("SELECT 1 AS x").execute().subscribe().with(
                        rs -> {
                            completions.add(System.nanoTime() - t0);
                            latch.countDown();
                        },
                        e -> {
                            failures.incrementAndGet();
                            latch.countDown();
                        });
            }
            boolean done = latch.await(30, TimeUnit.SECONDS);
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Object> data = Map.of("maxSize", maxSize, "total", total, "failures", failures.get(),
                    "singleMs", singleMs, "wallMs", wallMs, "latchDone", done);
            if (!done || failures.get() > 0 || completions.size() != total) {
                return new StepResult("A2", "pool", false, "并发排队异常", data);
            }
            return StepResult.pass("A2", "pool", data);
        } catch (Throwable e) {
            return StepResult.broken("A2", "pool", LabLog.brief(e));
        } finally {
            pool.close();
        }
    }

    private StepResult a3() {
        MySQLPool pool = LabEnv.newPool(2, 2); // connectionTimeout=2s
        try {
            SqlConnection c1 = pool.getConnection().await().atMost(Duration.ofSeconds(10));
            SqlConnection c2 = pool.getConnection().await().atMost(Duration.ofSeconds(10));
            long t0 = System.nanoTime();
            String err = null;
            boolean failed = false;
            try {
                pool.getConnection().await().atMost(Duration.ofSeconds(15));
            } catch (Throwable e) {
                failed = true;
                err = LabLog.brief(e);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            c1.close().subscribeAsCompletionStage();
            c2.close().subscribeAsCompletionStage();
            return new StepResult("A3", "pool", failed, failed ? null : "耗尽后 getConnection 竟然成功",
                    Map.of("timeoutMs", ms, "err", String.valueOf(err)));
        } catch (Throwable e) {
            return StepResult.broken("A3", "pool", LabLog.brief(e));
        } finally {
            pool.close();
        }
    }

    private StepResult a4() {
        MySQLPool pool = LabEnv.newPool(4, 0);
        try {
            SqlConnection victim = pool.getConnection().await().atMost(Duration.ofSeconds(10));
            Long victimId = victim.query("SELECT connection_id() AS cid").execute()
                    .map(rs -> rs.iterator().next().getLong("cid"))
                    .await().atMost(Duration.ofSeconds(10));
            SqlConnection killer = pool.getConnection().await().atMost(Duration.ofSeconds(10));
            killer.query("KILL " + victimId).execute().await().atMost(Duration.ofSeconds(10));
            killer.close().subscribeAsCompletionStage();

            String victimErr = null;
            try {
                victim.query("SELECT 1 AS x").execute().await().atMost(Duration.ofSeconds(10));
            } catch (Throwable e) {
                victimErr = LabLog.brief(e);
            }

            String recovery = null;
            for (int i = 1; i <= 3; i++) {
                try {
                    pool.preparedQuery("SELECT 1 AS x").execute().await().atMost(Duration.ofSeconds(10));
                    recovery = "第" + i + "次池查询成功";
                    break;
                } catch (Throwable e) {
                    if (i == 3) {
                        recovery = "3次仍失败: " + LabLog.brief(e);
                    }
                }
            }
            victim.close().subscribeAsCompletionStage();
            boolean ok = recovery != null && recovery.contains("成功");
            return new StepResult("A4", "pool", ok, ok ? null : recovery,
                    Map.of("victimId", victimId, "victimErr", String.valueOf(victimErr), "recovery", recovery));
        } catch (Throwable e) {
            return StepResult.broken("A4", "pool", LabLog.brief(e));
        } finally {
            pool.close();
        }
    }

    private StepResult a5() {
        MySQLPool pool = LabEnv.newPool(8, 0);
        try {
            Row r = pool.preparedQuery("SELECT connection_id() AS cid, current_user() AS u").execute()
                    .await().atMost(Duration.ofSeconds(10)).iterator().next();
            return StepResult.pass("A5", "pool",
                    Map.of("connectionId", r.getLong("cid"), "currentUser", String.valueOf(r.getString("u"))));
        } catch (Throwable e) {
            return StepResult.broken("A5", "pool", LabLog.brief(e));
        } finally {
            pool.close();
        }
    }
}
