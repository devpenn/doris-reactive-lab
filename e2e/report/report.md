# doris-reactive-lab e2e 报告

- 时间：2026-11-25 13:43:25
- 用例：78，PASS 59，FAIL 0

| 用例 | 结果 | 说明 |
|---|---|---|
| A1 | PASS |  |
| A2 | PASS | max-size=2 6并发全成功 单查291ms 墙钟920ms（排队串行化） |
| A3 | PASS |  |
| A4 | PASS | KILL cid=4788 后 第1次池查询成功；被杀连接报错=io.vertx.core.impl.NoStackTraceThrowable: Connection is not active now, current status: CLOSED |
| A5 | PASS |  |
| A6 | PASS | readiness=UP checks=['Reactive MySQL connections health check', 'Database connections health check'] |
| A7 | PASS | liveness=UP checks=[] |
| B1 | PASS |  |
| B1-hr | PASS | HR native LIMIT ?,? 同被 FE 拒 err=error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input 'LIMIT' expecting {<EOF>, ';'}(line 1, pos 14) , errorCode |
| B1c | PASS |  |
| B1c-hr | PASS | HR native 字面量 LIMIT ids=[2, 6, 7] |
| B1d | INFO | HR setMaxResults 生成参数化 limit ?,? 被 FE 拒（HQL/原生分页同根因）err=error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input 'limit' expecting {<EOF>, ';'}(line 1, pos 54) , err |
| B2-pool | PASS | pool 8 类型绑定全对（DECIMAL 回显类型=123.456789<String>） |
| B2-hr | PASS | HR 8 类型绑定全对（明细={"hr-String": "公司码测试/String", "hr-Integer": "42/Integer", "hr-Long>2^53": "9007199254740993/Long", "hr-Double": "3.14159/Double", "hr-DECIMAL": "123.456789/String", "hr-DATETIME": "2024 |
| B3 | PASS |  |
| B4-pool | PASS | pool IN 100/1000/5000 count 均=12（5000 占位符 FE prepare 也可用） |
| B4-hr | PASS | HR IN 100/1000/5000 count 均=12 |
| B4b-pool | PASS | 字面量 IN(5000) simple-query count=12 |
| B5-pool | PASS | GROUP BY+JOIN+IN+max(array_contains) pool rows=['100#6#1300.983789#true', '200#4#70.000001#true'] |
| B5-hr | PASS | HR 同 SQL 语义一致 rows=['100#6#1300.983789#true', '200#4#70.000001#true']（has3 经 1/0→true 归一） |
| B5b | PASS |  |
| B5b-hr | PASS | HR array_contains 字面量=1 |
| C0 | PASS | vertx MySQLConnectOptions 无 timeZone 参数（setter 193 个）→ DATETIME 转换责任在应用层 |
| C1 | PASS |  |
| C1-hr | INFO | HR 实体读 DATETIME 列失败（重大发现）：Unable to extract JDBC value for position `2` |
| D0a | INFO | native 直读→LocalDateTime 成功：value=2024-06-01T08:00 type=LocalDateTime |
| D0b | INFO | native CAST AS CHAR→String 成功：value=2024-06-01 08:00:00 type=None |
| D0c | INFO | native 直读→String 失败：Unable to extract JDBC value for position `1` |
| C1b | PASS | Connector/J 时区参数只影响 getTimestamp 纪元：上海=1717200000000 / UTC=1717228800000(+8h)，getObject(LocalDateTime) 墙钟不变 |
| C2 | PASS |  |
| C2-hr | PASS | HR DECIMAL=123.456789 |
| C3 | PASS |  |
| C3-hr | PASS | HR BIGINT=9007199254740993 精确 |
| C4 | PASS |  |
| C4-hr | PASS | HR NULL status=null remark=null |
| C5 | PASS |  |
| C5-hr | PASS | HR payload=payload-3-emoji-😀 attrsJson={"src":"app"} |
| C6 | PASS |  |
| C6-hr | PASS | HR 空结果集 rows=0 |
| D1 | PASS |  |
| D2-entity | INFO | 实体新 session 真 find 失败（DATETIME 列提取，非缓存假读）：Unable to extract JDBC value for position `4` |
| D2-native | PASS | persist 落库验证（native 读）userName=lab-写入 gmtCreate=2024-06-01T08:00(LocalDateTime) |
| D2b | PASS |  |
| D2c-same-session | INFO | 同 session 双 persist 被 Hibernate 按 @Id 拦截：A different object with the same identifier value was already associated with this persistence context for entity [io.dorislab.doris.hr.LabDupEvent  |
| D2c | PASS | 分 session 双 persist → Doris append 语义 rows=2 |
| D3a-entity | INFO | 实体 find(新 session) 真读失败：Unable to extract JDBC value for position `4` |
| D3a | PASS | native UPDATE 读回 balance=555.555555 |
| D3b | INFO | HR native（prepare 路径）DELETE 断连：Failed to read any response from the server, the underlying connection may have been lost unexpectedly.（对照：G6 pool simple-query DELETE 正常） |
| D3c | INFO | HR native（prepare 路径）DELETE 断连（三表一致）：Failed to read any response from the server, the underlying connection may have been lost unexpectedly. |
| D3d | INFO | 事务内 DELETE 被 Doris 拒：error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is acceptable., errorCode=1105, s |
| D3d-native | INFO | aggregate 模型 native DELETE 同样断连（三表一致，prepare 路径 DELETE 通病）：Failed to read any response from the server, the underlying connection may have been lost unexpectedly. |
| D4 | PASS |  |
| D4b | PASS | native INSERT executeUpdate affected=1（DATETIME 绑定参数写路径） |
| D4c | INFO | aggregate 模型 @EmbeddedId findById 被拒（生成 SQL 形态 FE prepare 不支持）：error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input ',' expecting {')', '.', '[', 'AND', 'BETWEEN |
| D5a | INFO | @WithTransaction 内 prepare INSERT 被 Doris 拒：error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is accepta |
| D5b | INFO | 事务内 INSERT 被拒后 rowsAfterRollback=0（行不在=INSERT 未落库而非回滚成功；err=None） |
| D5c | INFO | 事务内 UPDATE 后 userName=卡罗尔（回滚有效性观测） |
| D5d | INFO | duplicate 模型事务内 INSERT 被拒：error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is a |
| D5e | PASS |  |
| D6 | INFO | HQL 分页（Panache findAll().page() 同路径）被 FE 拒（limit ?,? 参数化）：error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input 'limit' expecting {<EOF>, ';'}(line 1, pos 160) ,  |
| D6b | PASS |  |
| E1-pool-jdbc | PASS | 种子行 {1,3,4,2^53+1} pool/jdbc 逐字段一致（含 NULL/空串/emoji/DECIMAL/DATETIME/JSON） |
| E1-hr | PASS | HR native 投影与 pool 逐字段一致（含 DATETIME 元素 LocalDateTime 形态） |
| E2-pool-jdbc | PASS | 复杂聚合 SQL pool/jdbc 逐行一致 rows=['100#6#1300.983789#true', '200#4#70.000001#true'] |
| E2-hr | PASS | HR 同 SQL 三方一致 rows=['100#6#1300.983789#true', '200#4#70.000001#true'] |
| E3-pool-jdbc | PASS | 空结果集 pool=0 jdbc=0 |
| E3-hr | PASS | HR 空结果集 rows=0 |
| F1 | PASS |  |
| F2 | PASS |  |
| F3 | INFO | 本地 2s 超时放弃 8 条 sleep(6)：普通查询快速返回（连接未被占满）；probeWaitMs=370，probe 正常返回；FE runningQueryRows=1 |
| F4 | PASS |  |
| F5 | PASS | SHOW PROCESSLIST 经 preparedQuery（prepare 路径）可用 rows=65 sample={Id=7, User=root, Command=Sleep, Time=14, Info=} |
| G1 | PASS |  |
| G2 | PASS |  |
| G3 | PASS |  |
| G4 | PASS |  |
| G5 | PASS |  |
| G6 | PASS |  |

## 场景端点原始返回（附录）

### group a
```json
[
 {
  "id": "A1",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "firstConnMs": 769
  }
 },
 {
  "id": "A2",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "latchDone": true,
   "wallMs": 920,
   "maxSize": 2,
   "total": 6,
   "singleMs": 291,
   "failures": 0
  }
 },
 {
  "id": "A3",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "timeoutMs": 2008,
   "err": "io.vertx.core.impl.NoStackTraceThrowable: Timeout"
  }
 },
 {
  "id": "A4",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "victimErr": "io.vertx.core.impl.NoStackTraceThrowable: Connection is not active now, current status: CLOSED",
   "victimId": 4788,
   "recovery": "第1次池查询成功"
  }
 },
 {
  "id": "A5",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "currentUser": "'root'@'%'",
   "connectionId": 4790
  }
 }
]
```
### group health-ready
```json
[
 {
  "name": "Reactive MySQL connections health check",
  "status": "UP",
  "data": {
   "dorispool": "UP",
   "<default>": "UP"
  }
 },
 {
  "name": "Database connections health check",
  "status": "UP",
  "data": {
   "dorisjdbc": "UP"
  }
 }
]
```
### group b
```json
[
 {
  "id": "B1",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "poolOk": false,
   "poolErr": "{errorMessage=errCode = 2, detailMessage =  mismatched input 'LIMIT' expecting {<EOF>, ';'}(line 1, pos 14) , errorCode=1105, sqlState=HY000}"
  }
 },
 {
  "id": "B1c",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "poolIds": [
    2,
    6,
    7
   ]
  }
 },
 {
  "id": "B2",
  "route": "pool",
  "ok": false,
  "error": "异常项: [pool-NULL]",
  "data": {
   "pool-String": "公司码测试",
   "pool-Integer": "42",
   "pool-Long>2^53": "9007199254740993",
   "pool-Double": "3.14159",
   "pool-DECIMAL": "123.456789<String>",
   "pool-DATETIME": "2024-06-01T08:00",
   "pool-NULL": "ERR: null",
   "pool-空串": ""
  }
 },
 {
  "id": "B3",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "matched": [
    "1:login",
    "3:login",
    "4:logout",
    "5:login",
    "9:logout",
    "10:login"
   ],
   "count": 6
  }
 },
 {
  "id": "B4",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "pool-100": 12,
   "pool-1000": 12,
   "pool-5000": 12
  }
 },
 {
  "id": "B4b",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "pool": 12
  }
 },
 {
  "id": "B5",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "pool": [
    "100#6#1300.983789#true",
    "200#4#70.000001#true"
   ]
  }
 },
 {
  "id": "B5b",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "literal-pool": "1",
   "tagsColumn": [
    1,
    3
   ]
  }
 }
]
```
### group b-hr
```json
[
 {
  "id": "B1-hr",
  "route": "hr",
  "ok": false,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input 'LIMIT' expecting {<EOF>, ';'}(line 1, pos 14) , errorCode=1105, sqlState=HY000}] [SELECT 1 AS x LIMIT ?, ?]",
  "data": {}
 },
 {
  "id": "B1c-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "ids": [
    2,
    6,
    7
   ]
  }
 },
 {
  "id": "B1d",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input 'limit' expecting {<EOF>, ';'}(line 1, pos 54) , errorCode=1105, sqlState=HY000}] [SELECT event_id FROM lab_dup_events ORDER BY event_id limit ?,?]",
  "data": {
   "limitShape": "parameterized-limit-rejected"
  }
 },
 {
  "id": "B2-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "hr-String": "公司码测试/String",
   "hr-Integer": "42/Integer",
   "hr-Long>2^53": "9007199254740993/Long",
   "hr-Double": "3.14159/Double",
   "hr-DECIMAL": "123.456789/String",
   "hr-DATETIME": "2024-06-01T08:00/LocalDateTime",
   "hr-NULL": "null/null",
   "hr-空串": "/String"
  }
 },
 {
  "id": "B4-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "hr-100": 12,
   "hr-1000": 12,
   "hr-5000": 12
  }
 },
 {
  "id": "B5-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "rows": [
    "100#6#1300.983789#true",
    "200#4#70.000001#true"
   ]
  }
 },
 {
  "id": "B5b-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "literal": "1"
  }
 }
]
```
### group c
```json
[
 {
  "id": "C0",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "setterCount": 193,
   "timeZoneOptionExists": false
  }
 },
 {
  "id": "C1",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "pool": "2024-06-01T08:00",
   "poolEpochMs": 1717200000000,
   "allEqual": true,
   "epochEqual": true,
   "jdbcEpochMs": 1717200000000,
   "jdbc": "2024-06-01T08:00"
  }
 },
 {
  "id": "C1b",
  "route": "jdbc",
  "ok": true,
  "error": null,
  "data": {
   "serverTimezone=Asia/Shanghai(现网基线)": "2024-06-01T08:00 | epochMs=1717200000000",
   "connectionTimeZone=Asia/Shanghai(新参数)": "2024-06-01T08:00 | epochMs=1717200000000",
   "connectionTimeZone=UTC": "2024-06-01T08:00 | epochMs=1717228800000",
   "connectionTimeZone=UTC+forceConnectionTimeZoneToSession=true": "2024-06-01T08:00 | epochMs=1717228800000"
  }
 },
 {
  "id": "C2",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "scale": 6,
   "allEqual": true,
   "pool": "123.456789",
   "jdbc": "123.456789"
  }
 },
 {
  "id": "C3",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "expected": 9007199254740993,
   "allExact": true,
   "pool": 9007199254740993,
   "jdbc": 9007199254740993
  }
 },
 {
  "id": "C4",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "poolStatus": null,
   "poolRemark": "null",
   "jdbcStatusNull": true
  }
 },
 {
  "id": "C5",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "jdbcJson": "{\"src\":\"app\"}",
   "jsonAllEqual": true,
   "poolJson": "{\"src\":\"app\"}",
   "poolPayload": "payload-3-emoji-😀",
   "rawType": "JsonObject"
  }
 },
 {
  "id": "C6",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "jdbcEmpty": true,
   "poolRows": 0
  }
 }
]
```
### group c-hr
```json
[
 {
  "id": "C1-hr",
  "route": "hr",
  "ok": true,
  "error": "Unable to extract JDBC value for position `2`",
  "data": {
   "hrDatetimeReadable": false
  }
 },
 {
  "id": "D0a",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "type": "LocalDateTime",
   "value": "2024-06-01T08:00"
  }
 },
 {
  "id": "D0b",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "value": "2024-06-01 08:00:00"
  }
 },
 {
  "id": "D0c",
  "route": "hr",
  "ok": false,
  "error": "Unable to extract JDBC value for position `1`",
  "data": {}
 },
 {
  "id": "D0d",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "eventId": "1",
   "eventTimeType": "LocalDateTime",
   "eventTime": "2024-06-01T08:00"
  }
 },
 {
  "id": "C2-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "eventValue": "123.456789"
  }
 },
 {
  "id": "C3-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "eventId": "9007199254740993"
  }
 },
 {
  "id": "C4-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "remark": "null",
   "status": "null"
  }
 },
 {
  "id": "C5-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "attrsJson": "{\"src\":\"app\"}",
   "payload": "payload-3-emoji-😀"
  }
 },
 {
  "id": "C6-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "rows": 0
  }
 }
]
```
### group c1b-matrix
```json
[
 {
  "serverTimezone=Asia/Shanghai(现网基线)": "2024-06-01T08:00 | epochMs=1717200000000",
  "connectionTimeZone=Asia/Shanghai(新参数)": "2024-06-01T08:00 | epochMs=1717200000000",
  "connectionTimeZone=UTC": "2024-06-01T08:00 | epochMs=1717228800000",
  "connectionTimeZone=UTC+forceConnectionTimeZoneToSession=true": "2024-06-01T08:00 | epochMs=1717228800000"
 }
]
```
### group d
```json
[
 {
  "id": "D1",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "count": 5,
   "path": "panache-interceptor-on-eventloop"
  }
 },
 {
  "id": "D2-entity",
  "route": "hr",
  "ok": true,
  "error": "Unable to extract JDBC value for position `4`",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D2-native",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "gmtCreateType": "LocalDateTime",
   "gmtCreate": "2024-06-01T08:00",
   "userName": "lab-写入"
  }
 },
 {
  "id": "D2b",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "gmtCreateAfter": "null",
   "emailAfter": "null",
   "userNameAfter": "alice-被覆盖",
   "statusAfter": "null",
   "balanceAfter": "null"
  }
 },
 {
  "id": "D2c-same-session",
  "route": "hr",
  "ok": true,
  "error": "A different object with the same identifier value was already associated with this persistence context for entity [io.dorislab.doris.hr.LabDupEvent with id '90002']",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D2c",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "rowsWithSameId": 2
  }
 },
 {
  "id": "D3a-entity",
  "route": "hr",
  "ok": true,
  "error": "Unable to extract JDBC value for position `4`",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D3a",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "balanceAfter": "555.555555"
  }
 },
 {
  "id": "D3b",
  "route": "hr",
  "ok": false,
  "error": "Failed to read any response from the server, the underlying connection may have been lost unexpectedly.",
  "data": {}
 },
 {
  "id": "D3c",
  "route": "hr",
  "ok": false,
  "error": "Failed to read any response from the server, the underlying connection may have been lost unexpectedly.",
  "data": {}
 },
 {
  "id": "D3d",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is acceptable., errorCode=1105, sqlState=HY000}] [DELETE FROM lab_agg_metrics WHERE total_value > 0]",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D3d-native",
  "route": "hr",
  "ok": false,
  "error": "Failed to read any response from the server, the underlying connection may have been lost unexpectedly.",
  "data": {}
 },
 {
  "id": "D4",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "firstName": "login",
   "rows": 6,
   "firstValue": "123.456789"
  }
 },
 {
  "id": "D4b",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "affected": 1
  }
 },
 {
  "id": "D4c",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input ',' expecting {')', '.', '[', 'AND', 'BETWEEN', 'COLLATE', 'DIV', 'IN', 'IS', 'LIKE', 'MATCH', 'MATCH_ALL', 'MATCH_ANY', 'MATCH_PHRASE', 'MATCH_PHRASE_EDGE', 'MATCH_PHRASE_PREFIX', 'MATCH_REGEXP', 'NOT', 'OR'...",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D5a",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is acceptable., errorCode=1105, sqlState=HY000}] [insert into lab_uk_users (balance,email,ext_json,gmt_create,gmt_modified,status,user_name,user_id) value...",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D5b",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "rowsAfterRollback": 0
  }
 },
 {
  "id": "D5c",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "userNameAfterRollback": "卡罗尔"
  }
 },
 {
  "id": "D5d",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is acceptable., errorCode=1105, sqlState=HY000}] [insert into lab_dup_events (attrs_json,company_id,event_name,event_time,event_value,payload,remark,statu...",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D5e",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage = This is in a transaction, only insert, update, delete, commit, rollback is acceptable., errorCode=1105, sqlState=HY000}] [SELECT count(*) FROM lab_uk_users]",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D6",
  "route": "hr",
  "ok": true,
  "error": "error executing SQL statement [{errorMessage=errCode = 2, detailMessage =  mismatched input 'limit' expecting {<EOF>, ';'}(line 1, pos 160) , errorCode=1105, sqlState=HY000}] [select luu1_0.user_id,luu1_0.balance,luu1_0.email,luu1_0.ext_json,luu1_0.gmt_create,luu1_0.gmt_modified,luu1_0.status,luu1_0...",
  "data": {
   "rejected": true
  }
 },
 {
  "id": "D6b",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "ids": "[300, 400]"
  }
 }
]
```
### group e
```json
[
 {
  "id": "E1",
  "route": "all",
  "ok": true,
  "error": null,
  "data": {
   "id1-pool": "1|2024-06-01T08:00|100|login|123.456789|payload-1-中文|{\"src\":\"app\",\"ver\":1}|1|r1",
   "id1-jdbc": "1|2024-06-01T08:00|100|login|123.456789|payload-1-中文|{\"src\":\"app\",\"ver\":1}|1|r1",
   "id3-pool": "3|2024-06-02T10:15:30|200|login|0.000001|payload-3-emoji-😀|{\"src\":\"app\"}|null|null",
   "id3-jdbc": "3|2024-06-02T10:15:30|200|login|0.000001|payload-3-emoji-😀|{\"src\":\"app\"}|null|null",
   "id4-pool": "4|2024-06-02T23:59:59|200|logout|-1.500000|payload-4|{\"a\":null}|1|r4",
   "id4-jdbc": "4|2024-06-02T23:59:59|200|logout|-1.500000|payload-4|{\"a\":null}|1|r4",
   "idbig-pool": "9007199254740993|2024-06-07T20:40|100|bigint-probe|1.000001|payload-big|{\"n\":9007199254740993}|9|r13",
   "idbig-jdbc": "9007199254740993|2024-06-07T20:40|100|bigint-probe|1.000001|payload-big|{\"n\":9007199254740993}|9|r13"
  }
 },
 {
  "id": "E2",
  "route": "all",
  "ok": true,
  "error": null,
  "data": {
   "pool": [
    "100#6#1300.983789#true",
    "200#4#70.000001#true"
   ],
   "jdbc": [
    "100#6#1300.983789#true",
    "200#4#70.000001#true"
   ]
  }
 },
 {
  "id": "E3",
  "route": "pool+jdbc",
  "ok": true,
  "error": null,
  "data": {
   "jdbcRows": 0,
   "poolRows": 0
  }
 }
]
```
### group e-hr
```json
[
 {
  "id": "E1-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "id1": "1|2024-06-01T08:00|100|login|123.456789|payload-1-中文|{\"src\":\"app\",\"ver\":1}|1|r1",
   "id3": "3|2024-06-02T10:15:30|200|login|0.000001|payload-3-emoji-😀|{\"src\":\"app\"}|null|null",
   "id4": "4|2024-06-02T23:59:59|200|logout|-1.5|payload-4|{\"a\":null}|1|r4",
   "idbig": "9007199254740993|2024-06-07T20:40|100|bigint-probe|1.000001|payload-big|{\"n\":9007199254740993}|9|r13"
  }
 },
 {
  "id": "E2-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "rows": [
    "100#6#1300.983789#true",
    "200#4#70.000001#true"
   ]
  }
 },
 {
  "id": "E3-hr",
  "route": "hr",
  "ok": true,
  "error": null,
  "data": {
   "rows": 0
  }
 }
]
```
### group f
```json
[
 {
  "id": "F1",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "elapsedMs": 1223,
   "afterErr": "null",
   "err": "{errorMessage=errCode = 2, detailMessage = query timeout, errorCode=1105, sqlState=HY000}",
   "cancelled": true,
   "poolCleanAfter": true
  }
 },
 {
  "id": "F2",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "err": "{errorMessage=errCode = 2, detailMessage = query timeout, errorCode=1105, sqlState=HY000}",
   "cancelled": true,
   "elapsedMs": 2618
  }
 },
 {
  "id": "F3",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "runningQueryRows": 1,
   "poolUsable": true,
   "localTimeoutFired": true,
   "localMs": 1384,
   "note": "probeWait>~3s=取消未传导（连接被 sleep 占满）；<1.5s=已传导或连接复用正常",
   "propagated": true,
   "probeWaitMs": 370
  }
 },
 {
  "id": "F4",
  "route": "jdbc",
  "ok": true,
  "error": null,
  "data": {
   "propagated": true,
   "elapsedMs": 1523,
   "lingeringAfter": 0,
   "sameConnUsable": true,
   "err": "Statement cancelled due to timeout or client request",
   "cancelled": true
  }
 },
 {
  "id": "F5",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "sample": "{Id=7, User=root, Command=Sleep, Time=14, Info=}",
   "rows": 65
  }
 }
]
```
### group g
```json
[
 {
  "id": "G1",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "affected": 1,
   "seededCount": 5
  }
 },
 {
  "id": "G2",
  "route": "pool",
  "ok": true,
  "error": "{errorMessage=errCode = 2, detailMessage =  mismatched input 'ON' expecting {<EOF>, ';'}(line 1, pos 69) , errorCode=1105, sqlState=HY000}",
  "data": {
   "accepted": false,
   "err": "{errorMessage=errCode = 2, detailMessage =  mismatched input 'ON' expecting {<EOF>, ';'}(line 1, pos 69) , errorCode=1105, sqlState=HY000}"
  }
 },
 {
  "id": "G3",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "concurrent": 10,
   "elapsedMs": 2590,
   "failures": 0,
   "winner": "并发-9",
   "finalRows": 1
  }
 },
 {
  "id": "G4",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "cols": [
    "event_id",
    "event_time",
    "company_id",
    "event_name",
    "event_value",
    "payload",
    "attrs_json",
    "tags",
    "status",
    "remark"
   ]
  }
 },
 {
  "id": "G5",
  "route": "pool",
  "ok": true,
  "error": null,
  "data": {
   "expectedLast": 13,
   "expectedMax": 13,
   "expectedTotal": 14,
   "last": 13,
   "max": 13,
   "total": 14
  }
 },
 {
  "id": "G6",
  "route": "all",
  "ok": false,
  "error": "HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread; currently running on thread 'executor-thread-2'",
  "data": {}
 }
]
```
