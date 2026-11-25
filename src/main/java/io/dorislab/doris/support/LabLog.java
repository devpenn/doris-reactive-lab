package io.dorislab.doris.support;

/**
 * 证据记录器：场景步骤输出 LAB|<id>|<route>|<status>|<detail> 行到应用日志，
 * 同时作为 COMPAT-MATRIX.md 的取证来源之一（断言在 e2e Python 层做）。
 */
public final class LabLog {

    private LabLog() {
    }

    public static void record(String id, String route, String status, String detail) {
        String d = String.valueOf(detail).replace('\n', ' ').replace('\r', ' ');
        System.out.printf("LAB|%s|%s|%s|%s%n", id, route, status, d);
    }

    public static String brief(Throwable e) {
        String msg = String.valueOf(e.getMessage()).replace('\n', ' ');
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }
}
