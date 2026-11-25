#!/usr/bin/env bash
# doris-reactive-lab e2e 编排：打包 → boot 变体实验（health-exclude/坏数据源/无方言）→ 主 boot → 跑 Python 用例 → 报告
# 用法：./run-e2e.sh            # 全量
#       ./run-e2e.sh --no-variants   # 跳过 boot 变体实验（调试用例层时）
set -uo pipefail
cd "$(dirname "$0")"

if [ ! -f local.env ]; then
  echo "[e2e] 缺少 local.env（凭据，gitignored）。参考 local.env.example。" >&2
  exit 1
fi
set -a
source local.env
set +a
# 主跑形态：显式 MySQL 方言（Doris 无专用 Hibernate 方言；不注方言的 NODIALECT 变体见 boot 实验区）
export QUARKUS_HIBERNATE_ORM_DIALECT=org.hibernate.dialect.MySQLDialect

# 参数：--no-variants 跳过 boot 变体实验；其余参数透传给 runner.py 作为组选择
NO_VARIANTS=false
if [ "${1:-}" = "--no-variants" ]; then
  NO_VARIANTS=true
  shift
fi

REPORT=e2e/report
mkdir -p "$REPORT"
VARIANTS_MD="$REPORT/boot-variants.md"
PORT=8081
APP_PID=""
BOOT_LOG=/tmp/doris-lab-boot-current.log

cleanup() { if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null; fi; }
trap cleanup EXIT

start_app() {
  java -jar target/quarkus-app/quarkus-run.jar > "$BOOT_LOG" 2>&1 &
  APP_PID=$!
}

wait_boot() {  # 返回 0=ready 端点有响应（不论 UP/DOWN）
  for _ in $(seq 1 45); do
    if curl -sk -o /dev/null "http://localhost:$PORT/q/health/ready"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

stop_app() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null
    wait "$APP_PID" 2>/dev/null
    APP_PID=""
  fi
}

health_summary() {  # python 摘要 /q/health/ready 与 live
  python3 - "$PORT" <<'PY'
import json, sys, urllib.request
port = sys.argv[1]
def fetch(p):
    try:
        with urllib.request.urlopen(f"http://localhost:{port}{p}", timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
    except Exception as e:
        return None, {"error": str(e)}
st, ready = fetch("/q/health/ready")
stl, live = fetch("/q/health/live")
checks = ready.get("checks", []) if isinstance(ready, dict) else []
print(f"ready HTTP={st} status={ready.get('status') if isinstance(ready, dict) else '-'}")
for c in checks:
    data = json.dumps(c.get("data"), ensure_ascii=False)[:150] if c.get("data") else ""
    print(f"  check: {c.get('name')} = {c.get('status')} {data}")
print(f"live HTTP={stl} status={live.get('status') if isinstance(live, dict) else '-'} checks={[c.get('name') for c in (live.get('checks', []) if isinstance(live, dict) else [])]}")
PY
}

# ─── 0) 打包 ───
echo "[e2e] mvn package ..."
mvn -q package -DskipTests || { echo "[e2e] 打包失败"; exit 1; }

echo "## boot 变体实验（$(date '+%F %T')）
" > "$VARIANTS_MD"

run_variant() {  # $1=名称 $2..=环境变量赋值
  local name="$1"; shift
  echo "--- 变体 ${name}：boot 中（env: $*）" >&2
  env "$@" java -jar target/quarkus-app/quarkus-run.jar > "$BOOT_LOG" 2>&1 &
  APP_PID=$!
  local booted=0
  if wait_boot; then
    booted=1
  fi
  {
    echo "### $name"
    echo "- 注入环境变量: \`$*\`"
    if [ "$booted" = "1" ]; then
      echo "- 结果: **启动成功**（ready 端点有响应）"
      health_summary | sed 's/^/  /'
    else
      echo "- 结果: **启动失败**（45s 内 ready 无响应）"
      echo '- 启动日志关键行:'
      grep -E "Caused by|Exception|ERROR" "$BOOT_LOG" | head -5 | sed 's/^/    /'
    fi
    echo ""
  } >> "$VARIANTS_MD"
  stop_app
  sleep 1
}

if [ "$NO_VARIANTS" = "false" ]; then
  run_variant "A8-数据源级health-exclude" "QUARKUS_DATASOURCE_DORISPOOL_HEALTH_EXCLUDE=true"
  run_variant "A8b-reactive级health-exclude" "QUARKUS_DATASOURCE_DORISPOOL_REACTIVE_HEALTH_EXCLUDE=true"
  run_variant "A9-dorispool坏地址" "QUARKUS_DATASOURCE_DORISPOOL_REACTIVE_URL=mysql://127.0.0.1:1/lab"
  run_variant "A9b-默认HR数据源坏地址+关版本核验" "QUARKUS_DATASOURCE_REACTIVE_URL=mysql://127.0.0.1:1/lab" "QUARKUS_HIBERNATE_ORM_DATABASE_VERSION_CHECK_ENABLED=false"
  # 无方言实验：临时去掉方言环境变量
  DIALECT_SAVE="${QUARKUS_HIBERNATE_ORM_DIALECT:-}"
  unset QUARKUS_HIBERNATE_ORM_DIALECT
  run_variant "NODIALECT-不指定方言启动"
  export QUARKUS_HIBERNATE_ORM_DIALECT="$DIALECT_SAVE"
fi

# ─── 主 boot + 用例 ───
echo "[e2e] 主 boot（显式 MySQL 方言）..."
start_app
if ! wait_boot; then
  echo "[e2e] 主 boot 失败，日志尾部："; tail -30 "$BOOT_LOG"
  exit 1
fi
echo "[e2e] 应用就绪，跑用例层 ..."
python3 e2e/runner.py "$@"
rc=$?
stop_app
echo "[e2e] 完成 rc=${rc}；报告: e2e/report/report.md；boot 变体: $VARIANTS_MD"
exit $rc
