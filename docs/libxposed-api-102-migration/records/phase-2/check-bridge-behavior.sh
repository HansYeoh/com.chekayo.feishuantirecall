#!/usr/bin/env bash
# 阶段 2 审计修复行为验证：宿主 JVM 直跑桥接层行为测试（不进 APK、不依赖真机）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-2/check-bridge-behavior.sh
# 覆盖：hookAll* 通配数量一致性、空数组 vs null 通配语义、registry key 固化、callStaticMethod 静态筛选。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
PHASE2="$REPO/docs/libxposed-api-102-migration/records/phase-2"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
BRIDGE="$REPO/app/src/main/java/com/chekayo/feishuantirecall"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
# MSYS 侧工具(unzip)用 POSIX 路径；Windows 侧工具(javac/java)要 Windows 路径，
# 且带 SEP 的组合 classpath 不会被 MSYS 自动转换，必须显式 cygpath -m。
if command -v cygpath >/dev/null 2>&1; then
  WORKJ="$(cygpath -m "$WORK")"
else
  WORKJ="$WORK"
fi

echo "== 阶段2 桥接层行为验证（宿主 JVM） =="

JAVAC="$(command -v javac || true)"; [ -z "$JAVAC" ] && [ -n "${JAVA_HOME:-}" ] && JAVAC="$JAVA_HOME/bin/javac"
JAVA_BIN="$(command -v java || true)"; [ -z "$JAVA_BIN" ] && [ -n "${JAVA_HOME:-}" ] && JAVA_BIN="$JAVA_HOME/bin/java"
[ -n "$JAVAC" ] && [ -n "$JAVA_BIN" ] || fail "jdk not found (set JAVA_HOME)"

# android.jar（编译期签名引用 + 运行期兜底；测试路径不触达其 stub 方法体）
# 注意 set -u：所有可选环境变量必须带 :- 缺省，LOCALAPPDATA 未导出时不得中断（复审 P2 修补项）
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

# 全量 app 源码 + 本测试：ModulePath 依赖 AntiRecall/ResignTracker（阶段3+ 引入），
# 桥接层验证入口必须随生产代码依赖面同步扩为全源码编译（2026-10 审计修复：原脚本只编译
# 桥接五类，ModulePath 新增直接依赖后编译失败，验证入口失效）。
# 注意源码根是 $APP（含 feishuantirecall + larkresign 两个包），$BRIDGE 只是其中之一。
APP="$REPO/app/src/main/java"
"$JAVAC" -encoding UTF-8 \
  -cp "$WORKJ/classes.jar$SEP$ANDROID_JAR" \
  -d "$WORKJ/out" \
  $(find "$APP" -name '*.java') \
  "$PHASE2/workbench/HookBridgeBehaviorTest.java" \
  2> "$WORK/javac.err"
[ $? -ne 0 ] && { cat "$WORK/javac.err" >&2; fail "javac failed"; }
echo "javac OK"

"$JAVA_BIN" -cp "$WORKJ/out$SEP$WORKJ/classes.jar$SEP$ANDROID_JAR" HookBridgeBehaviorTest
exit $?
