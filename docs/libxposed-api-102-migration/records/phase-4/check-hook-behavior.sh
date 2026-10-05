#!/usr/bin/env bash
# 阶段 4 出口验证 2：宿主 JVM 直跑迁移后 hook 回调的行为等价测试（不进 APK、不依赖真机）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-4/check-hook-behavior.sh
# 覆盖：ReadReqHook 浏览清空/暂存、回复窗口合并回填、开关关透传、短参安全放行；
#       SendReqHook 开窗；InvokeHook 现状不短路；MapperHook 缓存/回填/状态恢复；
#       RestrictedModeUnlock 共享门禁短路语义；HookRuntime 幂等注册。
# 运行时 classpath 含 android.jar（Log.INFO 编译期内联，日志走 FakeXposed no-op），
# 无 stubs —— 本身即"业务回调不依赖 legacy 类型"的运行级证明。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
PHASE4="$(cd "$(dirname "$0")" && pwd)"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
APP="$REPO/app/src/main/java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
if command -v cygpath >/dev/null 2>&1; then WORKJ="$(cygpath -m "$WORK")"; else WORKJ="$WORK"; fi

echo "== 阶段4 hook 迁移行为验证（宿主 JVM） =="

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

# 全量编译：全部 app 源码 + 本测试（无 stubs classpath）
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$WORKJ/classes.jar;$ANDROID_JAR" \
  -d "$WORKJ/out" \
  $(find "$APP" -name '*.java') "$PHASE4/workbench/HookMigrationBehaviorTest.java" \
  2> "$WORK/javac.err" || { cat "$WORK/javac.err" >&2; fail "javac failed"; }
echo "javac OK（无 stubs）"

# 运行：cwd 收在临时目录，业务首次落盘不污染仓库
mkdir -p "$WORK/run"
cd "$WORK/run"
"$JAVA_BIN" -cp "$WORKJ/out$SEP$WORKJ/classes.jar$SEP$ANDROID_JAR" \
  com.chekayo.feishuantirecall.HookMigrationBehaviorTest
exit $?
