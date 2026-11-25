# doris-reactive-lab

**English**: A compatibility lab that systematically validates two reactive access routes — *Hibernate Reactive + Panache* and *quarkus-reactive-mysql-client (MySQLPool)* — against **Apache Doris 4.1.4 over the MySQL protocol** with Quarkus 3.34.5. Full compatibility matrix and conclusions: [COMPAT-MATRIX.md](COMPAT-MATRIX.md) (Chinese). All tests are runnable locally via e2e-style cases; see §8 of the matrix for methodology.

实验性独立仓：对 **测试环境 Doris 4.1.4（MySQL 协议）** 系统性验证两条响应式技术路线的兼容性，
产出兼容性矩阵与选型结论。**结论见 [COMPAT-MATRIX.md](COMPAT-MATRIX.md)。**

## 架构（e2e-test 形态，无 JUnit）

```
Quarkus lab 应用（场景执行引擎）
  ├─ /scenario/a|b|c|e|f|g      pool/jdbc 腿（@Blocking worker 线程）
  ├─ /scenario/b-hr|c-hr|e-hr   HR 腿（eventloop 限定——HR 客户端唯一可靠线程，矩阵结论）
  ├─ /scenario/d                Hibernate Reactive/Panache 专项（eventloop 响应式链）
  ├─ /scenario/setup            lab_ 建表+种子（幂等）/ reseed
  └─ /q/health/*                smallrye-health（A6~A9 场景宿主）
e2e/（Python 用例层，仅标准库）
  ├─ runner.py                  七组用例：调用场景端点→断言→LAB| 证据行→report.md
  └─ lab_lib.py                 HTTP 驱动 / 断言 / 报告
run-e2e.sh（编排）
  打包 → boot 变体实验（health-exclude×2/坏数据源×2/无方言）→ 主 boot → 跑用例 → 报告
```

线程分工是矩阵结论的镜像：**HR 腿只能在 eventloop**（worker 触发 HR000068、裸线程触发 safe-context 拒绝），
pool/jdbc 腿与含 sleep 的破坏性实验放 worker（@Blocking）。

## 两条路线 + 一条对照基线

| 路线 | 依赖 | 数据源 | 入口 |
|---|---|---|---|
| A. quarkus-reactive-mysql-client（MySQLPool） | `quarkus-reactive-mysql-client` | 命名 `dorispool` | `@ReactiveDataSource("dorispool") MySQLPool`（对齐 生产项目用法） |
| B. Hibernate Reactive + Panache | `quarkus-hibernate-reactive-panache` | 默认（仅 reactive） | `PanacheRepositoryBase` / `Mutiny.SessionFactory` / `@WithSession` / `@WithTransaction` |
| C. JDBC（现网基线对照） | `quarkus-jdbc-mysql` + `quarkus-agroal` | 命名 `dorisjdbc` | AgroalDataSource，URL 形态对齐生产项目 starrocks 数据源（serverTimezone=Asia/Shanghai） |

版本：Quarkus 3.34.5（io.quarkus.platform:quarkus-bom，与生产项目（Quarkus 3.34.5）对齐）、Java 25。

## 测试表（全部 `lab_` 前缀，不碰任何现有表）

`support/LabSetup` 幂等建表 + 种子（IF NOT EXISTS + TRUNCATE + 定值 INSERT），覆盖 Doris 三表模型：

- `lab_dup_events` — DUPLICATE KEY 模型（含 STRING/JSON/ARRAY<INT>/DECIMAL/DATETIME/NULL/emoji/BIGINT>2^53 探针列）
- `lab_uk_users` — UNIQUE KEY 模型（MOW，实体映射目标：无自增主键、手工 @Id）
- `lab_agg_metrics` — AGGREGATE KEY 模型（SUM/REPLACE/MAX 三种聚合列，@EmbeddedId 实体映射实验）

## 跑法

```bash
# 凭据：cp local.env.example local.env 并填入 dev-test Doris 密码（local.env 已 gitignore，勿提交）
./run-e2e.sh                    # 全量（含 boot 变体实验，~8 分钟）
./run-e2e.sh --no-variants      # 跳过 boot 变体（调试用例层）
./run-e2e.sh --no-variants b c  # 只跑指定组（a/health/b/c/d/e/f/g）
```

应用亦可独立起：`set -a; source local.env; set +a; export QUARKUS_HIBERNATE_ORM_DIALECT=org.hibernate.dialect.MySQLDialect; java -jar target/quarkus-app/quarkus-run.jar`（先 `mvn package -DskipTests`）。

所有断言结果输出 `LAB|<场景id>|<路线>|<PASS/FAIL/INFO>|<细节>` 行；报告 `e2e/report/report.md`、
boot 变体 `e2e/report/boot-variants.md`。

## 场景编号索引（详见 COMPAT-MATRIX.md）

- A1~A9b 连接与池（建池耗时/max-size 排队/耗尽行为/kill 断线自愈/health check/health-exclude/坏数据源启动语义/无方言启动）
- B1~B5b、D0 SQL 与参数绑定（LIMIT ?/OFFSET ?/字面量 workaround/各类型绑定/LIKE/大 IN 列表/复杂聚合+array_contains/HR 读 DATETIME 边界）
- C0~C6 数据类型与行映射（DATETIME 时区三路线+Connector/J 参数矩阵/DECIMAL/BIGINT/NULL/TEXT+JSON/空结果集）
- D1~D6b Hibernate Reactive/Panache 专项（方言/实体映射/persist flush/upsert 语义/update-delete 三模型/session native/事务/分页）
- E1~E3 同 SQL 同参三路线逐字段对照
- F1~F5 慢查与超时（SET_VAR hint/session 变量/客户端取消传导/JDBC queryTimeout/processlist）
- G1~G6 其他（preparedBatch/ON DUPLICATE KEY/并发 upsert/information_schema/聚合模型写语义/emoji 写路径）

## 纪律

- 凭据只进 gitignored 的 `local.env`；任何会进 git 的文件不含密码。
- 只碰 `lab_*` 表；建表幂等（IF NOT EXISTS + TRUNCATE + 定值种子）。
- 
