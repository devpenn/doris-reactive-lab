package io.dorislab.doris.support;

import java.time.Duration;
import java.util.List;

import io.vertx.mutiny.mysqlclient.MySQLPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;

/**
 * lab_ 前缀测试表的建表 + 种子数据。幂等（IF NOT EXISTS + TRUNCATE + 定值 INSERT）。
 * 只碰 lab_* 表，不碰任何现有表。全部 SQL 为字面量，经 simple query 执行。
 * 由 POST /lab/setup 显式触发（e2e runner 起应用后先 setup 再跑用例）。
 */
public final class LabSetup {

    private static volatile boolean done;

    private LabSetup() {
    }

    public static synchronized String ensure() {
        if (done) {
            return "already";
        }
        MySQLPool pool = LabEnv.newPool(2, 30);
        try {
            run(pool, "SET SESSION query_timeout = 60");
            for (String ddl : ddlStatements()) {
                run(pool, ddl);
            }
            seedOn(pool);
            done = true;
            return "created";
        } finally {
            pool.close();
        }
    }

    /** 场景改坏数据后恢复种子 */
    public static synchronized void reseed() {
        MySQLPool pool = LabEnv.newPool(2, 30);
        try {
            seedOn(pool);
        } finally {
            pool.close();
        }
    }

    public static synchronized boolean isReady() {
        return done;
    }

    private static void run(MySQLPool pool, String sql) {
        RowSet<Row> rs = pool.query(sql).execute().await().atMost(Duration.ofSeconds(60));
        LabLog.record("SQL", "setup", "OK", abbreviate(sql));
    }

    private static String abbreviate(String sql) {
        String one = sql.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 120) + "... (" + sql.length() + " chars)" : one;
    }

    private static void seedOn(MySQLPool pool) {
        run(pool, "TRUNCATE TABLE lab_dup_events");
        run(pool, "TRUNCATE TABLE lab_uk_users");
        run(pool, "TRUNCATE TABLE lab_agg_metrics");
        for (String sql : seedStatements()) {
            run(pool, sql);
        }
    }

    public static List<String> ddlStatements() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS lab_dup_events (
                  event_id BIGINT NOT NULL COMMENT "事件ID",
                  event_time DATETIME NOT NULL COMMENT "事件时间",
                  company_id BIGINT NULL COMMENT "公司ID(关联lab_uk_users.user_id)",
                  event_name VARCHAR(128) NULL COMMENT "事件名",
                  event_value DECIMAL(18,6) NULL COMMENT "数值",
                  payload STRING NULL COMMENT "TEXT 形态列",
                  attrs_json JSON NULL COMMENT "JSON 列",
                  tags ARRAY<INT> NULL COMMENT "数组列",
                  status INT NULL COMMENT "可空整型",
                  remark VARCHAR(64) NULL COMMENT "可空字符串"
                ) ENGINE=OLAP
                DUPLICATE KEY(event_id, event_time)
                DISTRIBUTED BY HASH(event_id) BUCKETS 3
                PROPERTIES ("replication_num" = "1")
                """, """
                CREATE TABLE IF NOT EXISTS lab_uk_users (
                  user_id BIGINT NOT NULL COMMENT "用户ID=UNIQUE KEY",
                  user_name VARCHAR(64) NULL,
                  email VARCHAR(128) NULL,
                  balance DECIMAL(18,6) NULL DEFAULT "0",
                  status INT NULL,
                  gmt_create DATETIME NULL,
                  gmt_modified DATETIME NULL,
                  ext_json JSON NULL
                ) ENGINE=OLAP
                UNIQUE KEY(user_id)
                DISTRIBUTED BY HASH(user_id) BUCKETS 3
                PROPERTIES ("replication_num" = "1", "enable_unique_key_merge_on_write" = "true")
                """, """
                CREATE TABLE IF NOT EXISTS lab_agg_metrics (
                  biz_date DATE NOT NULL COMMENT "业务日期(aggregate key)",
                  company_id BIGINT NOT NULL,
                  metric_name VARCHAR(64) NOT NULL,
                  total_value BIGINT SUM DEFAULT "0",
                  last_value BIGINT REPLACE NULL,
                  max_value BIGINT MAX NULL
                ) ENGINE=OLAP
                AGGREGATE KEY(biz_date, company_id, metric_name)
                DISTRIBUTED BY HASH(company_id) BUCKETS 3
                PROPERTIES ("replication_num" = "1")
                """);
    }

    public static List<String> seedStatements() {
        return List.of("""
                INSERT INTO lab_dup_events (event_id, event_time, company_id, event_name, event_value, payload, attrs_json, tags, status, remark) VALUES
                (1, '2024-06-01 08:00:00', 100, 'login', 123.456789, 'payload-1-中文', '{"src":"app","ver":1}', [1,2,3], 1, 'r1'),
                (2, '2024-06-01 09:30:00', 100, 'pay', 999.999999, 'payload-2', '{"src":"wap"}', [2], 0, ''),
                (3, '2024-06-02 10:15:30', 200, 'login', 0.000001, 'payload-3-emoji-😀', '{"src":"app"}', [3,300], NULL, NULL),
                (4, '2024-06-02 23:59:59', 200, 'logout', -1.5, 'payload-4', '{"a":null}', [], 1, 'r4'),
                (5, '2024-06-03 12:00:00', 300, 'login', 456.789, 'payload-5', '{}', [7], 2, 'r5'),
                (6, '2024-06-03 13:00:00', 100, 'click', 10.5, 'payload-6', '{"src":"app"}', [1], 1, 'r6'),
                (7, '2024-06-04 14:00:00', 100, 'pay', 88.25, 'payload-7', '{"src":"ios"}', [2,7], 0, 'r7'),
                (8, '2024-06-04 15:30:00', 200, 'click', 5.0, 'payload-8', '{}', NULL, NULL, 'r8'),
                (9, '2024-06-05 16:00:00', 300, 'logout', 0, 'payload-9', 'null', [], 3, ''),
                (10, '2024-06-05 17:10:00', 100, 'login', 77.777, 'payload-10', '{"k":"v"}', [9], 1, 'r10'),
                (11, '2024-06-06 18:20:00', 200, 'pay', 66.5, 'payload-11-删除测试', '{"d":1}', [11], 1, 'r11'),
                (12, '2024-06-06 19:30:00', 300, 'click', 12.345678, 'payload-12', '{"src":"app"}', [1,2], 2, 'r12'),
                (9007199254740993, '2024-06-07 20:40:00', 100, 'bigint-probe', 1.000001, 'payload-big', '{"n":9007199254740993}', [13], 9, 'r13')
                """, """
                INSERT INTO lab_uk_users (user_id, user_name, email, balance, status, gmt_create, gmt_modified, ext_json) VALUES
                (100, 'alice', 'a@lab.local', 1000.000001, 1, '2024-01-01 10:00:00', '2024-01-02 11:00:00', '{"tier":"gold"}'),
                (200, 'bob', 'b@lab.local', 2000.500000, 0, '2024-02-01 10:00:00', '2024-02-01 12:30:00', '{}'),
                (300, '卡罗尔', 'c@lab.local', NULL, 1, '2024-03-01 08:30:00', NULL, NULL),
                (400, 'dave-😀', 'd@lab.local', 3000.000009, 2, '2024-04-01 00:00:00', '2024-04-01 00:00:00', '{"n":9007199254740993}'),
                (500, 'eve', NULL, 1.000000, 1, '2024-05-01 09:00:00', '2024-05-01 09:00:00', 'null')
                """, """
                INSERT INTO lab_agg_metrics (biz_date, company_id, metric_name, total_value, last_value, max_value) VALUES
                ('2024-06-01', 100, 'calls', 10, 10, 10),
                ('2024-06-01', 100, 'calls', 5, 5, 5),
                ('2024-06-01', 200, 'calls', 7, 7, 7),
                ('2024-06-02', 100, 'msgs', 3, 3, 3),
                ('2024-06-02', 100, 'msgs', 3, 3, 3),
                ('2024-06-02', 100, 'msgs', 3, 3, 3),
                ('2024-06-02', 300, 'msgs', 1, 1, 1)
                """);
    }
}
