#!/usr/bin/env bash
# 阶段 5 出口验证 1：hot reload 安全门控的静态门（06 文档阶段出口）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-5/check-hotreload-standalone.sh
# 断言：A 全量源码无 stubs 编译 OK（回归阶段 4 口径）；B 字节码零 legacy token 且消费 libxposed；
#       C 源码 07§3 模式零命中；D stubs 仅剩 LambdaMetafactory（不回潮）；
#       E module.prop 声明 autoHotReload=true 且 API 版本 102/102 未动；
#       F 门控接线：入口 onHotReloading 消费三类查询，四类资源创建点打标，HotReloadSafety
#         纯 java（无 Android 依赖），resetForTest 不被生产代码调用。
#       G java_init.list 单入口不变（阶段 3 语义回归）。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
APP="$REPO/app/src/main/java"
RES="$REPO/app/src/main/resources/META-INF/xposed"
B="$APP/com/chekayo/feishuantirecall"
LR="$APP/com/chekayo/larkresign"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
if command -v cygpath >/dev/null 2>&1; then WORKJ="$(cygpath -m "$WORK")"; else WORKJ="$WORK"; fi

echo "== 阶段5 hot reload 安全门控独立编译门 =="

JAVAC="$(command -v javac || true)"; [ -z "$JAVAC" ] && [ -n "${JAVA_HOME:-}" ] && JAVAC="$JAVA_HOME/bin/javac"
[ -n "$JAVAC" ] || fail "javac not found (set JAVA_HOME)"

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

# ── A. 全量编译：classpath 无任何 stubs ──
SRC_FILES="$(find "$APP" -name '*.java')"
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -cp "$WORKJ/classes.jar" \
  -d "$WORKJ/app" $SRC_FILES 2>"$WORK/javac.err" \
  || { cat "$WORK/javac.err" >&2; fail "全量编译失败"; }
echo "A. 全量编译 OK（classpath 仅 android.jar + libxposed classes.jar，无 stubs）"

# ── B. 字节码级零 legacy 引用 ──
for tok in 'de/robv' 'XposedBridge' 'XposedHelpers' 'XC_MethodHook' 'IXposedHook'; do
  hits="$(grep -r -a -l "$tok" "$WORKJ/app" | wc -l)"
  [ "$hits" = "0" ] || { grep -r -a -l "$tok" "$WORKJ/app"; fail "字节码出现 $tok（$hits 个 .class）"; }
done
grep -r -a -l -q 'io/github/libxposed' "$WORKJ/app" || fail "字节码未消费 libxposed API"
echo "B. 字节码零 legacy token（de/robv、XposedBridge、XposedHelpers、XC_MethodHook、IXposedHook 均 0），libxposed API 在用"

# ── C. 源码级 07 文档 §3 同模式扫描 ──
if command -v rg >/dev/null 2>&1; then
  rg -n 'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook|IXposedHook|xposed_init' "$APP" \
    && fail "源码 07§3 模式扫描仍有命中"
else
  grep -rnE 'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook|IXposedHook|xposed_init' "$APP" \
    && fail "源码 07§3 模式扫描仍有命中"
fi
echo "C. 源码扫描零命中（07§3 全模式）"

# ── D. stubs 目录仅剩 lambda 编译桩 ──
[ ! -d "$REPO/stubs/de" ] || fail "stubs/de 仍存在"
[ -f "$REPO/stubs/java/lang/invoke/LambdaMetafactory.java" ] || fail "LambdaMetafactory 编译桩被误删"
STUB_N="$(find "$REPO/stubs" -name '*.java' | wc -l)"
[ "$STUB_N" = "1" ] || fail "stubs 应只剩 LambdaMetafactory 1 个文件，实际 $STUB_N"
echo "D. stubs 仅剩 java/lang/invoke/LambdaMetafactory.java"

# ── E. module.prop：autoHotReload 声明在位、格式正确、API 版本未动（06 阶段出口 1） ──
PROP="$RES/module.prop"
tr -d '\r' < "$PROP" | grep -q '^autoHotReload=true$' || fail "module.prop 缺 autoHotReload=true"
tr -d '\r' < "$PROP" | grep -q '^minApiVersion=102$' || fail "module.prop minApiVersion 被改动"
tr -d '\r' < "$PROP" | grep -q '^targetApiVersion=102$' || fail "module.prop targetApiVersion 被改动"
tr -d '\r' < "$PROP" | grep -q '^exceptionMode=protective$' || fail "module.prop exceptionMode 被改动"
echo "E. module.prop：autoHotReload=true 在位，102/102 与 exceptionMode=protective 未动"

# ── F. 门控接线 ──
grep -q 'HotReloadSafety.hasNativeHooks()' "$B/FeishuKitModule.java" || fail "入口未消费门控一"
grep -q 'HotReloadSafety.hasModuleThreads()' "$B/FeishuKitModule.java" || fail "入口未消费门控二"
grep -q 'HotReloadSafety.hasExternalCallbacks()' "$B/FeishuKitModule.java" || fail "入口未消费门控三"
grep -q 'HotReloadSafety.markNativeHook' "$B/AntiRecall.java" || fail "AntiRecall 未登记 native hook"
grep -q 'HotReloadSafety.markThread' "$B/AntiRecall.java" || fail "AntiRecall 未登记安装线程"
grep -q 'HotReloadSafety.markExternalCallback' "$B/AntiRecall.java" || fail "AntiRecall 未登记配置桥接收器"
grep -q 'HotReloadSafety.markExternalCallback' "$B/DownloadMirror.java" || fail "DownloadMirror 未登记 FileObserver"
grep -q 'HotReloadSafety.markThread' "$LR/ResignTracker.java" || fail "ResignTracker 未登记线程"
grep -q 'HotReloadSafety.markNativeHook' "$LR/ResignTracker.java" || fail "ResignTracker 未登记 native hook"
grep -q 'HotReloadSafety.beginDelayedTask' "$B/ProfileCapture.java" || fail "ProfileCapture 未登记延时任务"
grep -q 'HotReloadSafety.endDelayedTask' "$B/ProfileCapture.java" || fail "ProfileCapture 未配对递减延时任务"
if grep -rnE 'import android|android\.' "$B/HotReloadSafety.java"; then
  fail "HotReloadSafety 应为纯 java（宿主 JVM 可测，无 Android 依赖）"
fi
PROD_CALLERS="$(grep -rl 'resetForTest' "$APP" | grep -v 'HotReloadSafety.java' || true)"
[ -z "$PROD_CALLERS" ] || { echo "$PROD_CALLERS"; fail "生产代码不得调用 resetForTest"; }
echo "F. 门控接线 OK：入口消费三类查询，AntiRecall/ResignTracker/DownloadMirror/ProfileCapture 打标齐全，HotReloadSafety 纯 java"

# ── G. 元数据不变：java_init.list 单入口（阶段 3 语义回归） ──
[ "$(tr -d '\r' < "$RES/java_init.list" | tr -d '\n')" = "com.chekayo.feishuantirecall.FeishuKitModule" ] \
  || fail "java_init.list 入口被改动"
echo "G. java_init.list 单入口不变 = FeishuKitModule"

echo "== PASS：阶段5 hot reload 安全门控独立编译门 =="
