package io.dorislab.doris.scenario;

import java.util.Map;

/**
 * 场景步骤结果（Java 执行层只执行与取证，断言在 e2e/ Python 层做）。
 * ok=步骤执行形态是否符合该步骤的预期（探针预期失败的步骤 ok=true 表示「符合预期地失败」）；
 * data=断言所需的原始数据。
 */
public record StepResult(String id, String route, boolean ok, String error, Map<String, Object> data) {

    public static StepResult pass(String id, String route, Map<String, Object> data) {
        return new StepResult(id, route, true, null, data == null ? Map.of() : data);
    }

    /** 探针按预期失败（如 LIMIT ? 被 FE 拒绝）——ok=true，error 携带原始报错 */
    public static StepResult expectedFail(String id, String route, String error, Map<String, Object> data) {
        return new StepResult(id, route, true, error, data == null ? Map.of() : data);
    }

    /** 执行层意外失败（不是任何预期形态，e2e 层一律判 FAIL） */
    public static StepResult broken(String id, String route, String error) {
        return new StepResult(id, route, false, error, Map.of());
    }

    public static StepResult info(String id, String route, Map<String, Object> data) {
        return new StepResult(id, route, true, null, data == null ? Map.of() : data);
    }
}
