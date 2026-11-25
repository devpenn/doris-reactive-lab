## boot 变体实验（2026-11-25 13:41:12）

### A8-数据源级health-exclude
- 注入环境变量: `QUARKUS_DATASOURCE_DORISPOOL_HEALTH_EXCLUDE=true`
- 结果: **启动成功**（ready 端点有响应）
  ready HTTP=200 status=UP
    check: Database connections health check = UP {"dorisjdbc": "UP"}
    check: Reactive MySQL connections health check = UP {"dorispool": "UP", "<default>": "UP"}
  live HTTP=200 status=UP checks=[]

### A8b-reactive级health-exclude
- 注入环境变量: `QUARKUS_DATASOURCE_DORISPOOL_REACTIVE_HEALTH_EXCLUDE=true`
- 结果: **启动成功**（ready 端点有响应）
  ready HTTP=200 status=UP
    check: Reactive MySQL connections health check = UP {"dorispool": "UP", "<default>": "UP"}
    check: Database connections health check = UP {"dorisjdbc": "UP"}
  live HTTP=200 status=UP checks=[]

### A9-dorispool坏地址
- 注入环境变量: `QUARKUS_DATASOURCE_DORISPOOL_REACTIVE_URL=mysql://127.0.0.1:1/lab`
- 结果: **启动成功**（ready 端点有响应）
  ready HTTP=503 status=DOWN
    check: Reactive MySQL connections health check = DOWN {"dorispool": "down - connection failed: Connection refused: /127.0.0.1:1", "<default>": "UP"}
    check: Database connections health check = UP {"dorisjdbc": "UP"}
  live HTTP=200 status=UP checks=[]

### A9b-默认HR数据源坏地址+关版本核验
- 注入环境变量: `QUARKUS_DATASOURCE_REACTIVE_URL=mysql://127.0.0.1:1/lab QUARKUS_HIBERNATE_ORM_DATABASE_VERSION_CHECK_ENABLED=false`
- 结果: **启动成功**（ready 端点有响应）
  ready HTTP=503 status=DOWN
    check: Database connections health check = UP {"dorisjdbc": "UP"}
    check: Reactive MySQL connections health check = DOWN {"dorispool": "UP", "<default>": "down - connection failed: Connection refused: /127.0.0.1:1"}
  live HTTP=200 status=UP checks=[]

### NODIALECT-不指定方言启动
- 注入环境变量: ``
- 结果: **启动成功**（ready 端点有响应）
  ready HTTP=200 status=UP
    check: Reactive MySQL connections health check = UP {"dorispool": "UP", "<default>": "UP"}
    check: Database connections health check = UP {"dorisjdbc": "UP"}
  live HTTP=200 status=UP checks=[]

