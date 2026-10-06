#!/usr/bin/env bash
# 2026-10 分支审计修复回归（宿主 JVM 直跑，不进 APK、不依赖真机）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/audit-fixes/check-audit-fix-behavior.sh
# 覆盖：F1（宿主异常上抛且原方法只执行一次：AiPeekBlock addView/setVisibility、
#       ReadReqHook 短参数、InvokeHook 提前放行分支、MapperHook 提前返回分支）、
#       F2（onHotReloading 无条件拒绝：已装 hook/分发开始过/状态表清空三形态都拒绝；
#       零 hook 安装成功用例要求独立换代 + 抛错框架真实收到安装尝试——复审 P3 隔离要求）、
#       F4（findMethodExact 不上溯父类、int/Integer 严格区分；callMethod best-match 保留）、
#       F5（动态实现类 hook ID 含声明类+签名；同键不同 Executable 报冲突；同键幂等保留）。
#       F3（module.prop staticScope=false 动态作用域）为元数据契约，由本脚本的 grep 断言覆盖。
# 运行时 classpath 含 android.jar（stub 抛错即各业务安装点 fail-soft），无 stubs。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
FIXDIR="$(cd "$(dirname "$0")" && pwd)"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
APP="$REPO/app/src/main/java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
if command -v cygpath >/dev/null 2>&1; then WORKJ="$(cygpath -m "$WORK")"; else WORKJ="$WORK"; fi

echo "== 审计修复回归验证（宿主 JVM） =="

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

# F3：module.prop 动态作用域契约（保留白标支持，scope.list 两个默认推荐包不变）
grep -q '^staticScope=false$' "$REPO/app/src/main/resources/META-INF/xposed/module.prop" \
  || fail "module.prop staticScope must be false (audit F3)"
printf 'com.ss.android.lark\ncom.larksuite.suite\n' | diff -q - "$REPO/app/src/main/resources/META-INF/xposed/scope.list" >/dev/null \
  || fail "scope.list default recommended packages changed (audit F3 keeps whitelist support)"
echo "F3 metadata OK（staticScope=false + 默认推荐 scope 不变）"

# 全量编译：全部 app 源码 + 本测试（无 stubs classpath）
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$WORKJ/classes.jar$SEP$ANDROID_JAR" \
  -d "$WORKJ/out" \
  $(find "$APP" -name '*.java') "$FIXDIR/workbench/AuditFixBehaviorTest.java" \
  2> "$WORK/javac.err" || { cat "$WORK/javac.err" >&2; fail "javac failed"; }
echo "javac OK（无 stubs）"

# 运行：cwd 收在临时目录，业务首次落盘不污染仓库
mkdir -p "$WORK/run"
cd "$WORK/run"
"$JAVA_BIN" -cp "$WORKJ/out$SEP$WORKJ/classes.jar$SEP$ANDROID_JAR" \
  com.chekayo.feishuantirecall.AuditFixBehaviorTest
exit $?
