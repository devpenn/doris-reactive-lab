package io.dorislab.doris.support;

import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.PoolOptions;

/**
 * 实验环境连接参数。全部来自环境变量（run-e2e.sh 注入 local.env），密码绝不入库。
 * 默认值仅含 host/port/db（与 生产项目 application.properties 同源公开值），密码无默认。
 */
public final class LabEnv {

    private LabEnv() {
    }

    public static String host() {
        return env("LAB_DORIS_HOST", "127.0.0.1");
    }

    public static int port() {
        return Integer.parseInt(env("LAB_DORIS_PORT", "9030"));
    }

    public static String db() {
        return env("LAB_DORIS_DB", "lab");
    }

    public static String user() {
        return env("LAB_DORIS_USER", "root");
    }

    public static String password() {
        return System.getenv().getOrDefault("LAB_DORIS_PASSWORD", "");
    }

    public static boolean credentialsPresent() {
        return !password().isEmpty();
    }

    public static MySQLConnectOptions connectOptions() {
        return new MySQLConnectOptions()
                .setHost(host())
                .setPort(port())
                .setDatabase(db())
                .setUser(user())
                .setPassword(password())
                .setConnectTimeout(10_000);
    }

    /** 程序化建池（池行为实验用，与应用管理的 dorispool 隔离）。
     * Vertx 上下文内（Quarkus @Blocking worker / eventloop）必须显式传 Vertx 实例，
     * 否则 vertx 驱动拒绝建池（"Running in a Vertx context => use Pool#pool(Vertx, ...)"）。 */
    public static io.vertx.mutiny.mysqlclient.MySQLPool newPool(int maxSize, int connectionTimeoutSeconds) {
        PoolOptions po = new PoolOptions().setMaxSize(maxSize);
        if (connectionTimeoutSeconds > 0) {
            po.setConnectionTimeout(connectionTimeoutSeconds);
        }
        io.vertx.core.Context vctx = io.vertx.core.Vertx.currentContext();
        if (vctx != null) {
            io.vertx.mutiny.core.Vertx mutinyVertx = new io.vertx.mutiny.core.Vertx(vctx.owner());
            return io.vertx.mutiny.mysqlclient.MySQLPool.pool(mutinyVertx, connectOptions(), po);
        }
        return io.vertx.mutiny.mysqlclient.MySQLPool.pool(connectOptions(), po);
    }

    /** JDBC 基线参数，形态照搬 生产项目 starrocks 数据源（形态参考） URL */
    public static String baselineJdbcParams() {
        return "useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull"
                + "&allowMultiQueries=true&serverTimezone=Asia/Shanghai";
    }

    public static String jdbcUrl(String extraParams) {
        String base = "jdbc:mysql://" + host() + ":" + port() + "/" + db();
        if (extraParams == null || extraParams.isEmpty()) {
            return base;
        }
        return base + "?" + extraParams;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
