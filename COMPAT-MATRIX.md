# doris-reactive-lab 兼容性矩阵与结论

> 生成：2026-11-25 · 环境：dev-test Doris（`dev-test Doris 实例（地址与库名略）`，**Doris 4.1.4-rc04**，MySQL 协议兼容版本上报 `5.7.99`） · Quarkus **3.34.5**· Java 25 · vertx-mysql-client 4.5.26 · hibernate-reactive-core 3.2.9.Final · MySQL Connector/J（quarkus-jdbc-mysql 同版）
>
> 全部结论均有本仓 e2e 实跑证据（78 用例，PASS 59 + INFO 观测 19，FAIL 0；跑法见 [README](README.md)）。证据行格式 `LAB|<场景>|<路线>|<状态>|<细节>`，定版全量输出存 `/tmp/doris-lab-e2e-final.log`，报告与 boot 变体在 `e2e/report/`。
>
> 三条路线：**pool** = quarkus-reactive-mysql-client（MySQLPool，`@ReactiveDataSource("dorispool")`，生产项目已上线的同名用法）；**HR** = Hibernate Reactive + Panache（默认数据源，仅 reactive）；**jdbc** = JDBC/Agroal（URL 形态对齐生产项目 starrocks 数据源，serverTimezone=Asia/Shanghai，文本协议）。

## 零、一句话结论

**数仓查询（生产数仓查询场景）选 MySQLPool 路线；HQL/Panache 实体映射与事务、分页、DELETE 这三块在 HR 路线上被 Doris 硬性阻断，不可用。**

## 一、总览

| 维度 | MySQLPool（preparedQuery） | Hibernate Reactive + Panache | JDBC/Agroal（文本协议） |
|---|---|---|---|
| SELECT 参数绑定 | ✅ | ✅（native 显式类型投影） | ✅ |
| LIMIT/OFFSET 参数化 | ❌ FE prepare 拒（`mismatched input 'LIMIT'`，B1） | ❌ setMaxResults 生成 `limit ?,?` 同拒（B1d/D6） | ✅ 客户端内联，无感 |
| 字面量 LIMIT | ✅（B1c workaround） | ✅ native 字面量（B1c-hr/D6b workaround） | ✅ |
| 实体/HQL 映射 | —（无此层） | ❌ 含 DATETIME 列的实体读必炸（C1-hr/D2-entity/D3a-entity） | ✅（现网正常使用） |
| INSERT（prepare） | ✅ | ✅（native；**实体 persist 必须显式 flush，否则静默丢弃**，D2-native） | ✅ |
| UPDATE（prepare） | ✅ | ✅ native（D3a）；实体 merge 因读炸不可用 | ✅ |
| DELETE（prepare） | ✅ **仅 simple query 实证**（G6） | ❌ **native DELETE 直接断连**（D3b/D3c/D3d-native 三表一致） | ✅（文本协议） |
| 事务 | ❌ 不适用（连接级） | ❌ `@WithTransaction` 内所有 prepare DML 被 Doris 拒（D5a/D5d） | — |
| 分页 | 字面量 LIMIT ✅ | 仅 native 字面量 LIMIT ✅；Panache `.page()` ❌ | ✅ |
| health check | ✅ 注册且可观测（A6/A9） | ✅（默认数据源） | ✅ |

## 二、场景明细

### 场景一：连接与池（pool 路线，A 组）

| # | 场景 | 结果 | 证据/细节 |
|---|---|---|---|
| A1 | 建池+首连+首查 | ✅ | 实测 ~540ms（含 TCP+认证） |
| A2 | max-size 并发行为 | ✅ 排队不报错 | max-size=2 时 6 并发全成功，墙钟≈⌈6/2⌉×单查（304ms→1283ms） |
| A3 | 池耗尽行为 | ⚠️ **挂等待而非快速失败** | 第 3 个 `getConnection` 等 `connectionTimeout`（默认 **60s**）后失败；可配小（实测 2s 精确生效）。生产建议显式设小并配超时兜底 |
| A4 | kill 断线重连 | ✅ 池自愈 | `KILL <connection_id>` 后被杀连接报 `Connection is not active now: CLOSED`，**下一次池查询即自动重建成功** |
| A5 | 配置路径建池 | ✅ | 与程序化路径一致 |
| A6/A7 | health check | ✅ | reactive 池注册为「Reactive MySQL connections health check」、JDBC 为「Database connections health check」（同一 check 聚合本类所有数据源，`data` 里按池列明细）；**liveness 不含任何数据源 check**（DB 挂不触发重启风暴） |
| A8/A8b | health-exclude | ❌ **两属性均不生效** | `quarkus.datasource.<ds>.health-exclude=true` 与 `<ds>.reactive.health-exclude=true` 注入后，check 的 `data` 里 **dorispool 条目仍在**（boot 变体实证）。⚠️ 生产项目注释认为该属性排除了 doris 健康检查——**该认知在 3.34.5 上不成立**，数仓不可达时 readiness 仍会 DOWN |
| A9 | 命名池坏地址 | ✅ 启动成功 | 懒连接：boot 成功，readiness 503 仅该池条目 DOWN，liveness UP |
| A9b | 默认（HR）数据源坏地址 | ⚠️ | 默认关「版本核验」时启动直接失败（SessionFactory 创建即连库核验版本）；`quarkus.hibernate-orm.database.version-check.enabled=false` 后可懒启动（变体实证） |
| NODIALECT | 不指定方言启动 | ✅ | Quarkus 自动探测方言可启动（但生产仍建议显式，避免依赖探测行为） |

**D1 方言（HR 路线）**：Doris 无专用 Hibernate 方言；显式 `org.hibernate.dialect.MySQLDialect` 可启动。**但必须钉 `quarkus.datasource.db-version=5.7.99`**——Doris 线上报 MySQL 5.7.99，Quarkus MySQL 方言默认最低 8.0.0，不钉则启动时版本核验直接拒（`ConfigurationException ... actual version is '5.7.99'`，首轮实跑原始报错）。

### 场景二：SQL 与参数绑定（B 组）

| # | 场景 | pool | HR | 说明/坑 |
|---|---|---|---|---|
| B1 | `LIMIT ?, ?` 参数化 | ❌ `mismatched input 'LIMIT'` | ❌ 同拒 | 已知坑复现（spike 结论仍有效，Doris 4.1.4 未解决）。**workaround：数值内联**（生产项目现行策略，Number 强转拼接无注入面） |
| B1c | 字面量 LIMIT + 其余参数化 | ✅ ids=[2,6,7] 正确 | ✅ 同 | 主 workaround |
| B1d/D6 | `setMaxResults/setFirstResult` | — | ❌ 生成 `limit ?,?` → FE 拒 | **HR/Panache 分页（`.page()`）全部不可用**；workaround=native 字面量 LIMIT（D6b ids=[300,400] ✅） |
| B2 | 类型绑定 String/Integer/Long>2^53/Double/DECIMAL/DATETIME/NULL/空串 | ✅ 8/8 | ✅ 8/8 | 坑：①**DECIMAL 参数 prepare 回显类型漂移为 String**（值正确）；②HR native 单列回显字符串包装为 **ClobProxy**（需 `getSubString` 解包） |
| B3 | LIKE 参数 | ✅ `LIKE CONCAT('%',?,'%')` | ✅ | count=6 与种子一致 |
| B4 | 大 IN 列表 Tuple 绑定 100/1000/5000 | ✅ count=12 | ✅ count=12 | **5000 占位符 FE prepare 也可用**；workaround 字面量内联 IN(5000) 亦可用（B4b，simple query） |
| B5 | GROUP BY+JOIN+IN+array_contains | ✅ | ✅ | 注意：聚合 SELECT 里的 ARRAY 列表达式要包 `max(...)`，裸 `array_contains(e.tags,3)` 出现在 GROUP BY 查询的 SELECT 列表会报 `tags not in aggregate's output`（首轮 E2 实跑捕获） |
| B5b | array_contains 字面量/ARRAY 列 | ✅ `[1,2,3]` 字面量、`array_contains(tags, ?)` 列形态均可用 | ✅ 字面量 | ARRAY 列普通 WHERE 可用（聚合上下文见上） |

### 场景三：数据类型与行映射（C 组）

| # | 场景 | pool | HR | jdbc | 结论 |
|---|---|---|---|---|---|
| C0 | vertx 时区参数 | ❌ 不存在 | ❌ | — | `MySQLConnectOptions` 无 timeZone 参数（193 个 setter 实测），**DATETIME 时区语义由应用层负责** |
| C1 | DATETIME 三路线墙钟 | ✅ `2024-06-01T08:00` | （native ✅） | ✅ | vertx 返回原始 LocalDateTime；pool 的 LocalDateTime 按 `Asia/Shanghai` 转 epoch == JDBC Timestamp 纪元（**report `toDate()` 转换模式等价旧 serverTimezone 语义**，等价性实证） |
| C1b | Connector/J 时区参数矩阵 | — | — | ✅ | `serverTimezone=Asia/Shanghai` ≡ `connectionTimeZone=Asia/Shanghai`（新参数等价旧参数）；`connectionTimeZone=UTC` 只影响 `getTimestamp` 纪元（1717200000000→1717228800000 +8h），`getObject(LocalDateTime)` 墙钟不变 |
| C2 | DECIMAL(18,6) 精度 | ✅ scale=6 | ✅ | ✅ | 三方值/精度无损 |
| C3 | BIGINT 2^53+1 | ✅ 精确 | ✅ | ✅ | 9007199254740993 三路线读数精确 |
| C4 | NULL | ✅ getInteger→null | ✅ | ✅ | vertx 对 NULL 列返回 null（等价 getInt+wasNull） |
| C5 | TEXT/JSON 列 | ⚠️ STRING ✅；**JSON 列解码为 JsonObject**（`getString` 抛 CCE，须 `getValue→encode`） | ⚠️ STRING 列 native 读回 **ClobProxy**（须解包）；JSON 列同理 | ✅ 按 String | emoji（😀）全程无损 |
| C6 | 空结果集 | ✅ | ✅ | ✅ | 三路线 0 行正常 |

### 场景四：Hibernate Reactive / Panache 专项（D 组，重点）

| # | 场景 | 结果 | 说明 |
|---|---|---|---|
| D1 | 方言/配置/启动 | ✅ | 显式 MySQL 方言 + `db-version=5.7.99` 启动；Panache 拦截器（`@WithSession`）在 eventloop 上工作正常 |
| D2 | persist→真读 | ⚠️❌ | 两个坑：①**同 session find 命中一级缓存是"假读"**（不发 SQL，断言全过但根本没读库）；②开新 session 真 SELECT 即炸 `Unable to extract JDBC value`（DATETIME 列）。**persist 还必须显式 `s.flush()`——withSession 关闭不自动 flush，不 flush 的 persist 静默丢弃**（D2-native 证明 flush 后数据真落库） |
| D2b | unique 模型 upsert | ✅（native 验证） | 重复主键 persist 不报错、**整行覆盖**（未赋值字段→null），与 MySQL DuplicateKeyException 语义根本不同 |
| D2c | duplicate 模型 append | ✅（分 session） | 同 session 双 persist 被 **Hibernate 自己按 @Id 拦截**（到不了 Doris）；分 session 双 persist → Doris append 语义 rows=2 |
| D3a | UPDATE | ✅ native UPDATE（实体 merge 因实体读炸不可用） | Doris unique MOW 模型 UPDATE 支持 ✅ |
| D3b/D3c/D3d-native | **native DELETE** | ❌❌❌ | **prepare 路径 DELETE 三表一致断连**：`Failed to read any response from the server`（SELECT/INSERT/UPDATE 的 prepare 全正常，唯 DELETE 断连；FE prepare 常规语法错误也只报错不断连，如 B1）。workaround：pool `query()`（simple query）DELETE 正常（G6 实证）/ JDBC 文本协议 |
| D3d | 事务内 DELETE | ❌ | `This is in a transaction, only insert, update, delete, commit, rollback is acceptable`（报错文案与实际行为不符，见 D5） |
| D4 | session 级 native query | ✅ | `?` 参数化 + 标量/Object[] 投影；**单列 native 返回标量而非 Object[]** |
| D4b | native INSERT 含 DATETIME 参数 | ✅ affected=1 | LocalDateTime 参数绑定写入 OK |
| D4c | aggregate 模型 @EmbeddedId findById | ❌ | 生成 SQL 被 FE prepare 拒（`mismatched input ','`，疑似行值/复合条件形态）；native 键列查询/写入（G5）正常——**聚合模型实体映射不可用，走 native** |
| D5a~D5e | **事务语义** | ❌ | **`@WithTransaction`/`sf.withTransaction` 内所有 prepare DML（INSERT/UPDATE/DELETE）一律被 Doris 拒**（文案称只允许 DML 实际全拒；D5b 的 rowsAfterRollback=0 是 INSERT 被拒未落库，**不是**回滚成功）；事务内 SELECT 同拒（D5e）。Doris SQL 事务 + prepare 路径 = 实质不可用 |
| D6 | Panache `.page()` 分页 | ❌ | 同 B1d 根因 |
| D6b | native 字面量 LIMIT 分页 | ✅ | workaround 有效 |

**线程模型（两轮实跑硬约束）**：
1. Panache 拦截器（`@WithSession`/`@WithTransaction`）只能在 **Vertx eventloop**（REST 订阅点）上调——worker 报 `No current Vertx context`，裸线程同样拒绝；
2. HR 客户端（含裸 `Mutiny.SessionFactory`）在 **Quarkus @Blocking worker** 上报 `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread`，在裸线程上报 `requires a safe (isolated) Vert.x sub-context`，**只有 eventloop 可靠**；
3. 嵌套 `withSession` 会复用同一 session（同 id 双 persist 被拦）——需要独立 session 必须在外层平铺。

### 场景五：三方语义对照（E 组）

| # | 场景 | 结果 |
|---|---|---|
| E1 | 同 SQL 同参，pool/HR(native 投影)/JDBC 逐字段 | ✅ 三方一致（种子行 1/3/4/2^53+1，覆盖 NULL/空串/emoji/DECIMAL/DATETIME/JSON；注意三方均需把 JSON 列按文本归一后比对） |
| E2 | 复杂聚合 SQL 三方 | ✅ 逐行一致 |
| E3 | 空结果集三方 | ✅ |

### 场景六：慢查与超时（F 组）

| # | 场景 | 结果 | 说明 |
|---|---|---|---|
| F1 | FE 侧取消（`/*+ SET_VAR(query_timeout=1) */`） | ✅ **推荐姿势** | ~1.3s 收 `query timeout` 错误；**池安全**（不污染会话），连接干净归还 |
| F2 | FE 侧取消（`SET SESSION query_timeout=1`） | ✅ 生效 | 但**池上不可用**（session 变量污染连接），仅独立连接形态 |
| F3 | 客户端取消（Mutiny `ifNoItem` 超时） | ⚠️ 取消不传导 | 本地超时放弃后 FE 上查询继续跑完；本轮 8 条占满形态 probe 快速返回（370ms），前轮占满形态 probeWaitMs≈2.5s——两种形态都有观测，**结论：取消不传导 FE，且被放弃命令的连接恢复期可能拿到错位响应**。生产须以 FE 侧超时（F1）为主 |
| F4 | JDBC `setQueryTimeout(1)` | ✅ 传导 | ~1.5s 抛超时异常，**Connector/J 经 KILL QUERY 把取消传导到 FE**（sleep(5) 残留=0），同连接仍可用 |
| F5 | SHOW PROCESSLIST 经 prepare | ✅ 可用 | 注意：prepare 路径的**运行中查询在 processlist 的 Info 不可见**（观测口径只能按 Command=Query 计数）；text 协议查询 Info 正常可见 |

### 场景七：其他场景（G 组）

| # | 场景 | 结果 | 说明 |
|---|---|---|---|
| G1 | preparedBatch 批量写 | ✅ | 一次往返 5 行（mutiny Tuple 无 varargs `of`，>6 参用 `Tuple.from(Object[])`；另 mutiny Tuple **无 addNull**） |
| G2 | `INSERT ... ON DUPLICATE KEY` | ❌ | `mismatched input 'ON'`——unique 模型 INSERT 即 upsert，无此语法 |
| G3 | 10 并发同键 upsert | ✅ | 全部成功、终态单行（MySQL 同场景必 DuplicateKeyException）；winner 非确定（最后写入者） |
| G4 | information_schema 参数化 | ✅ | 列清单正确 |
| G5 | aggregate 模型 INSERT 即聚合 | ✅ | SUM 1+13=14 / REPLACE=13 / MAX=13 全符合模型语义 |
| G6 | emoji+中文写路径 | ✅ | pool 绑定写 → HR 读回一致；simple-query DELETE 正常（HR prepare DELETE 的对照） |
| — | 环境故障韧性 | ⚠️ 记录 | 一轮实跑遇 dev-test BE `MEM_LIMIT_EXCEEDED` 取消查询（共享测试库内存压力），表现为偶发单查询失败；应用侧场景需容忍重试 |

## 三、必须规避（HR 路线在 Doris 上的硬阻断）

1. **实体映射到含 DATETIME 列的表**：真 SELECT 必炸 `Unable to extract JDBC value`（D0 界定：native 显式 `LocalDateTime.class` ✅、CAST AS CHAR ✅、getString/实体提取 ❌）——即 **HR/Panache 实体模型在 Doris 上读写都不可靠**；
2. **所有 HR 路线 DELETE**（native 或实体 remove）：prepare 路径 DELETE 断连（三表一致）；
3. **事务**（`@WithTransaction` / `sf.withTransaction`）：事务内 prepare DML/SELECT 全拒；
4. **分页**（`setMaxResults` / Panache `.page()`）：生成参数化 LIMIT 被 FE 拒；
5. **同 session 双 persist 同 id**（Hibernate 自身拦截）；**persist 后不 flush**（静默丢弃）；
6. aggregate 模型 **@EmbeddedId 实体 findById**（生成 SQL 被 FE prepare 拒）。

## 四、需要 workaround

| 场景 | workaround | 证据 |
|---|---|---|
| 分页 | native SQL + **字面量 LIMIT/OFFSET**（数值内联，无注入面） | B1c/D6b |
| DELETE | MySQLPool `query()`（simple query）或 JDBC 文本协议；**禁用 HR 路线 DELETE** | G6 vs D3b/c |
| 实体时间字段 | 不映射进实体；时间列读取走 native 显式类型（`LocalDateTime.class`）或 `CAST AS CHAR` | D0a/D0b vs D0c/C1-hr |
| HR persist | 显式 `s.flush()`（withSession 关闭不自动 flush） | D2-native |
| 超时取消 | FE 侧 hint `/*+ SET_VAR(query_timeout=N) */`（池安全）；禁用纯客户端超时当取消手段 | F1 vs F3 |
| 方言/启动 | `quarkus.hibernate-orm.dialect=MySQLDialect` + `quarkus.datasource.db-version=5.7.99`（可加 `version-check.enabled=false` 换取坏库懒启动） | D1/A9b/NODIALECT 变体 |
| DATETIME 时区 | vertx 无时区参数，应用层按 `Asia/Shanghai` 显式转换（report `toDate()` 模式，等价性已实证） | C1/C1b |
| JSON 列（pool） | `row.getValue(col)` → JsonObject → `encode()`（getString 抛 CCE） | C5 |
| 聚合 SELECT 的 ARRAY 列表达式 | 包 `max(array_contains(...))` | B5/E2 vs 首轮 E2 |

## 五、选型建议（数仓查询迁移）

1. **主路线：MySQLPool（quarkus-reactive-mysql-client）**，延续生产项目已上线的响应式试点（MySQLPool + LIMIT 字面量内联）已实证的模式：
   - SELECT/INSERT/UPDATE 全可用；LIMIT 字面量内联沿用现行策略；5000 参数 IN、复杂聚合、ARRAY/JSON/中文/emoji 全通过；
   - 三方语义对照与现网 JDBC 逐字段一致，迁移无数据语义风险；
   - DELETE 用 `query()`（simple query）——数仓宽表/结果表场景 DELETE 频率低，成本可接受。
2. **不选 Hibernate Reactive + Panache**：实体映射（DATETIME 列）、事务、分页、DELETE 四块硬阻断，绕过成本 = 退化成"只用 native SQL 的 session 包装"，此时 Panache 的价值只剩注解线程管理，而其 eventloop 线程约束（①eventloop 限定 ②worker 拒绝 ③裸线程拒绝）反而成为额外负担。若未来 Doris 修复 prepare DELETE/事务内 DML/LIMIT ? 三项，可重评。
3. **JDBC（Agroal）继续作为需要 Connector/J 语义的场景的基线**（客户端内联 LIMIT、setQueryTimeout 传导、文本协议 DELETE）；响应式改造中它与 MySQLPool 并存无冲突（report 现行架构）。
4. **运维面提醒**：
   - `quarkus.datasource.<ds>.health-exclude` 对响应式数据源**无效**（A8/A8b 实测，生产项目注释需更正）；数仓不可达会进 readiness（合理），但不要指望该属性摘除；
   - 池耗尽默认挂 60s——生产给 `connectionTimeout` 配小值 + 上层超时；
   - 客户端取消不传导 FE：慢查取消必须用 `SET_VAR(query_timeout)` hint 或 FE 侧超时变量；
   - dev-test BE 偶发 `MEM_LIMIT_EXCEEDED`：批量迁移的回归测试要对单查询失败容忍重试。

## 六、证据索引

- 定版全量输出：`/tmp/doris-lab-e2e-final.log`（78 行 `LAB|` 证据）
- 用例报告：`e2e/report/report.md`（含场景端点原始 JSON 附录）
- boot 变体实验：`e2e/report/boot-variants.md`（A8/A8b/A9/A9b/NODIALECT 五组，含健康检查 JSON 明细）
- 历史轮次日志：`/tmp/doris-lab-e2e{2..21}.log`（含首轮启动版本核验失败、BE 内存超限等原始报错）
- 上游参考：生产项目 `DorisReactiveSpikeTest`（spike 结论：LIMIT ?,? 拒、字面量 LIMIT 过——本 lab 在 Doris 4.1.4 上全部复核成立）
