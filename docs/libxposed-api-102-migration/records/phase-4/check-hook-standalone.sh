#!/usr/bin/env bash
# 阶段 4 出口验证 1：全部 app 源码在【无 stubs、无 legacy 类型】的 classpath 下独立编译，
# 字节码级 + 源码级双重零 legacy 引用（05 文档阶段出口 + 07 文档 §3 扫描口径）。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-4/check-hook-standalone.sh
# 与阶段 3 的差别：阶段 3 需要两步编译（业务类当时仍依赖 legacy stubs）；
# 阶段 4 起业务 hook 全部走 HookRuntime/Reflect/ModuleLog，stubs/de/robv 已删除，
# 全量源码一次编译的 classpath 只有 android.jar + libxposed classes.jar。
# 断言：A 全量无 stubs 编译 OK；B 字节码零 legacy token 且消费 libxposed API；
#       C 源码 07§3 模式零命中；D stubs 仅剩 LambdaMetafactory；
#       E hook 注册全部收口 HookRuntime（业务文件不得自触 HookBuilder/intercept）。
set -u

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
AAR="$REPO/third_party/libxposed/api-102.0.0.aar"
APP="$REPO/app/src/main/java"
RES="$REPO/app/src/main/resources/META-INF/xposed"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "[FAIL] $*" >&2; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
if command -v cygpath >/dev/null 2>&1; then WORKJ="$(cygpath -m "$WORK")"; else WORKJ="$WORK"; fi

echo "== 阶段4 hook 迁移独立编译门 =="

JAVAC="$(command -v javac || true)"; [ -z "$JAVAC" ] && [ -n "${JAVA_HOME:-}" ] && JAVAC="$JAVA_HOME/bin/javac"
[ -n "$JAVAC" ] || fail "javac not found (set JAVA_HOME)"

# android.jar；set -u：可选环境变量一律带 :- 缺省（阶段 2 复审 P2 修补项口径）
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
  || { cat "$WORK/javac.err" >&2; fail "无 stubs 全量编译失败（源码仍引用 legacy 类型）"; }
echo "A. 全量编译 OK（classpath 仅 android.jar + libxposed classes.jar，无 stubs）"

# ── B. 字节码级零 legacy 引用 ──
OUT="$WORKJ/app/com/chekayo/feishuantirecall"
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
echo "C. 源码扫描零命中（07§3 全模式：de.robv/XposedBridge/XposedHelpers/XC_MethodHook/IXposedHook/xposed_init）"

# ── D. stubs 目录仅剩 lambda 编译桩 ──
[ ! -d "$REPO/stubs/de" ] || fail "stubs/de 仍存在（阶段 4 应删除）"
[ -f "$REPO/stubs/java/lang/invoke/LambdaMetafactory.java" ] || fail "LambdaMetafactory 编译桩被误删（-source 8 需要）"
STUB_N="$(find "$REPO/stubs" -name '*.java' | wc -l)"
[ "$STUB_N" = "1" ] || fail "stubs 应只剩 LambdaMetafactory 1 个文件，实际 $STUB_N"
echo "D. stubs 仅剩 java/lang/invoke/LambdaMetafactory.java（de.robv 6 文件已删）"

# ── E. hook 注册收口：业务文件不得自触 HookBuilder / intercept 注册面 ──
B="$APP/com/chekayo/feishuantirecall"
for f in AiPeekBlock DataMigration RestrictedModeUnlock FileDownloadUnlock DownloadMirror \
         UpdateBanner ProfileCapture FuckLarkSettings AntiRecall; do
  grep -q 'HookRuntime\.' "$B/$f.java" || fail "$f.java 未走 HookRuntime 注册"
done
grep -q 'HookRuntime\.' "$REPO/app/src/main/java/com/chekayo/larkresign/ResignTracker.java" \
  && fail "ResignTracker 不应有 Java hook 注册（native inline hook 不经 HookRuntime）"
BAD="$(grep -rln 'HookBuilder\|\.intercept(' "$B" | grep -v 'HookRuntime.java' || true)"
[ -z "$BAD" ] || { echo "$BAD"; fail "HookBuilder/intercept 注册面泄漏到 HookRuntime 之外"; }
echo "E. 注册收口 OK：9 个业务 hook 文件全走 HookRuntime，无注册面泄漏"

# ── F. 元数据不变：java_init.list 单入口（阶段 3 语义回归） ──
[ "$(tr -d '\r' < "$RES/java_init.list" | tr -d '\n')" = "com.chekayo.feishuantirecall.FeishuKitModule" ] \
  || fail "java_init.list 入口被改动"
echo "F. java_init.list 单入口不变 = FeishuKitModule"

echo "== PASS：阶段4 hook 迁移独立编译门 =="
