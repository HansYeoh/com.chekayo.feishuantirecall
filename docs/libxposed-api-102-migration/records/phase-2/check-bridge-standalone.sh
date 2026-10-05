#!/usr/bin/env bash
# 阶段 2 出口验证：桥接层独立编译检查（零 de.robv 依赖）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-2/check-bridge-standalone.sh
# 只依赖 JDK + Android SDK platforms/android-*/android.jar + 仓库内 vendored AAR。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
PHASE2="$REPO/docs/libxposed-api-102-migration/records/phase-2"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
BRIDGE="$REPO/app/src/main/java/com/chekayo/feishuantirecall"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

echo "== 阶段2 桥接层独立编译检查 =="
echo "repo = $REPO"

# ── JDK ──
JAVAC="$(command -v javac || true)"
[ -z "$JAVAC" ] && [ -n "${JAVA_HOME:-}" ] && JAVAC="$JAVA_HOME/bin/javac"
[ -z "$JAVAC" ] && fail "javac not found (set JAVA_HOME)"
echo "javac = $JAVAC"

# ── android.jar（取 platforms 下版本最高者；set -u 下可选环境变量一律带 :- 缺省）──
SDK_CANDIDATES=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" "${HOME:-}/AppData/Local/Android/Sdk")
ANDROID_JAR=""
for sdk in "${SDK_CANDIDATES[@]}"; do
  [ -n "$sdk" ] && [ -d "$sdk/platforms" ] || continue
  for p in $(ls -1 "$sdk/platforms" | sort -V | tail -3); do
    [ -f "$sdk/platforms/$p/android.jar" ] && ANDROID_JAR="$sdk/platforms/$p/android.jar"
  done
  [ -n "$ANDROID_JAR" ] && break
done
[ -z "$ANDROID_JAR" ] && fail "android.jar not found (set ANDROID_HOME)"
echo "android.jar = $ANDROID_JAR"

# ── vendored AAR 内的 classes.jar（仅编译期）──
[ -f "$AAR" ] || fail "AAR missing: $AAR"
unzip -p "$AAR" classes.jar > "$WORK/classes.jar" || fail "extract classes.jar"
echo "classes.jar = $(wc -c < "$WORK/classes.jar") bytes (from ${AAR#$REPO/})"

# ── 编译：桥接层 5 类 + 最小 modern 入口，classpath 只有 android.jar + classes.jar ──
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$WORK/classes.jar" \
  -d "$WORK/out" \
  "$BRIDGE/ModuleRuntime.java" \
  "$BRIDGE/ModuleLog.java" \
  "$BRIDGE/Reflect.java" \
  "$BRIDGE/HookRuntime.java" \
  "$BRIDGE/ModulePath.java" \
  "$PHASE2/workbench/BridgeSmokeEntry.java" \
  2> "$WORK/javac.err"
rc=$?
[ $rc -ne 0 ] && { cat "$WORK/javac.err" >&2; fail "javac exit $rc"; }
echo "javac: $(find "$WORK/out" -name '*.class' | wc -l) classes compiled OK"

# ── 断言 1：产物零 de.robv 引用 ──
if grep -rl 'de/robv' "$WORK/out" >/dev/null 2>&1; then
  grep -rl 'de/robv' "$WORK/out"
  fail "compiled bridge classes reference de.robv"
fi
echo "assert: no de/robv reference in class files ... OK"

# ── 断言 2：确有 libxposed API 引用（证明真的消费了 API 102）──
HITS=$(grep -rl 'io/github/libxposed' "$WORK/out" | wc -l)
[ "$HITS" -ge 3 ] || fail "expected >=3 classes referencing io/github/libxposed, got $HITS"
echo "assert: $HITS classes reference io/github/libxposed ... OK"

echo "== PASS：桥接层在无 stubs 的最小 classpath 下独立编译通过，无 legacy 类型 =="
