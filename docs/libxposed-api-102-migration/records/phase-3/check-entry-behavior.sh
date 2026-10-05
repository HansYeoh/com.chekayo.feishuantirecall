#!/usr/bin/env bash
# 阶段 3 出口验证 2：宿主 JVM 直跑入口生命周期行为测试（不进 APK、不依赖真机）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-3/check-entry-behavior.sh
# 覆盖：onModuleLoaded 接线（bind / 进程名 / ModulePath fail-closed 与正路径）、
#       onPackageLoaded 不分发、非目标包过滤、国内/国际包完整分发一次、
#       幂等键 process+package+ClassLoader identity、hot reload fail-closed、跨代 bind 拒绝。
# 注意：测试 cwd 设在临时目录——分发链路里 Config 首次落盘会按相对盘符写
#       /data/data/...（Windows 下落到 cwd 同盘根），全部收在临时目录内随 trap 清理。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
PHASE3="$(cd "$(dirname "$0")" && pwd)"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
APP="$REPO/app/src/main/java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
if command -v cygpath >/dev/null 2>&1; then WORKJ="$(cygpath -m "$WORK")"; else WORKJ="$WORK"; fi

echo "== 阶段3 入口生命周期行为验证（宿主 JVM） =="

JAVAC="$(command -v javac || true)"; [ -z "$JAVAC" ] && [ -n "${JAVA_HOME:-}" ] && JAVAC="$JAVA_HOME/bin/javac"
JAVA_BIN="$(command -v java || true)"; [ -z "$JAVA_BIN" ] && [ -n "${JAVA_HOME:-}" ] && JAVA_BIN="$JAVA_HOME/bin/java"
[ -n "$JAVAC" ] && [ -n "$JAVA_BIN" ] || fail "jdk not found (set JAVA_HOME)"

ANDROID_JAR=""
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" "${HOME:-}/AppData/Local/Android/Sdk"; do
  [ -n "$sdk" ] && [ -d "$sdk/platforms" ] || continue
  for p in $(ls -1 "$sdk/platforms" | sort -V | tail -3); do
    [ -f "$sdk/platforms/$p/android.jar" ] && ANDROID_JAR="$sdk/platforms/$p/android.jar"
  done
  [ -n "$ANDROID_JAR" ] && break
done
[ -n "$ANDROID_JAR" ] || fail "android.jar not found (set ANDROID_HOME)"
command -v cygpath >/dev/null 2>&1 && ANDROID_JAR="$(cygpath -m "$ANDROID_JAR")"
echo "android.jar = $ANDROID_JAR"

[ -f "$AAR" ] || fail "AAR missing: $AAR"
unzip -p "$AAR" classes.jar > "$WORK/classes.jar" || fail "extract classes.jar"

# 全量编译：stubs + 全部 app 源码 + 本测试（业务 hook 走 stubs 空操作，可安全在宿主执行分发层）
"$JAVAC" --release 8 -d "$WORKJ/stubs" $(find "$REPO/stubs" -name '*.java') || fail "javac stubs failed"
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$WORKJ/stubs;$WORKJ/classes.jar;$ANDROID_JAR" \
  -d "$WORKJ/out" \
  $(find "$APP" -name '*.java') "$PHASE3/workbench/EntryBehaviorTest.java" \
  2> "$WORK/javac.err" || { cat "$WORK/javac.err" >&2; fail "javac failed"; }
echo "javac OK"

# 运行：cwd 收在临时目录，业务首次落盘不污染仓库
mkdir -p "$WORK/run"
cd "$WORK/run"
"$JAVA_BIN" -cp "$WORKJ/out$SEP$WORKJ/stubs$SEP$WORKJ/classes.jar$SEP$ANDROID_JAR" \
  com.chekayo.feishuantirecall.EntryBehaviorTest
exit $?
