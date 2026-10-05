#!/usr/bin/env bash
# 阶段 3 出口验证 1：modern 入口 + 桥接层在【无 stubs classpath】下独立编译，且字节码零 legacy 引用。
# 用法：在仓库根目录执行  bash docs/libxposed-api-102-migration/records/phase-3/check-entry-standalone.sh
# 与阶段 2 的差别：FeishuKitModule 按 04 文档要分发到业务类（AntiRecall 等仍依赖 legacy stubs，
# 阶段 4 才切换），所以独立编译分两步：
#   A. 先全量编译（stubs + 全部 app 源码）得到业务类 .class；
#   B. 再只重编 6 个 modern 源文件，classpath 只有 业务.class + android.jar + classes.jar，
#      stubs 完全不在 classpath —— 任何 modern 源引用 de.robv 都会直接编译失败。
# 断言：modern 字节码 de/robv=0；入口继承 XposedModule；源码无 legacy lifecycle 接口；
#       java_init.list 指向 FeishuKitModule。
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

echo "== 阶段3 入口独立编译门 =="

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

# ── A. 全量编译（stubs + 全部 app 源码）→ 业务类 .class ──
STUB_FILES="$(find "$REPO/stubs" -name '*.java')"
SRC_FILES="$(find "$APP" -name '*.java')"
"$JAVAC" --release 8 -d "$WORKJ/stubs" $STUB_FILES || fail "javac stubs failed"
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -cp "$WORKJ/stubs;$WORKJ/classes.jar" \
  -d "$WORKJ/app" $SRC_FILES 2>/dev/null || fail "javac app failed"
echo "A. 全量编译 OK（stubs + app）"

# ── B. modern-only 重编译：classpath 无 stubs ──
B="$APP/com/chekayo/feishuantirecall"
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -cp "$WORKJ/app;$WORKJ/classes.jar" \
  -d "$WORKJ/modern" \
  "$B/FeishuKitModule.java" "$B/ModuleRuntime.java" "$B/ModuleLog.java" \
  "$B/Reflect.java" "$B/HookRuntime.java" "$B/ModulePath.java" \
  || fail "modern-only compile failed（modern 源可能引用了 stubs）"
echo "B. modern-only 编译 OK（classpath 无 stubs）"

MOD="$WORK/modern/com/chekayo/feishuantirecall"
# MSYS grep 对二进制需 -a
[ "$(grep -r -a -l 'de/robv' "$MOD" | wc -l)" = "0" ] || { grep -r -a -l 'de/robv' "$MOD"; fail "modern 字节码出现 de/robv"; }
for c in FeishuKitModule ModuleRuntime ModuleLog HookRuntime ModulePath; do
  grep -a -q 'io/github/libxposed' "$MOD/$c.class" || fail "$c.class 未引用 libxposed API"
done
grep -a -q 'io/github/libxposed' "$MOD/Reflect.class" && fail "Reflect.class 不应引用 libxposed（纯反射工具）"
echo "断言1 OK：modern 字节码 de/robv=0；5 类消费 API，Reflect 零依赖"

# javap 的 -cp 必须是类路径根（modern/），不是叶子包目录
javap -cp "$WORKJ/modern" com.chekayo.feishuantirecall.FeishuKitModule > "$WORK/javap.txt" \
  || fail "javap FeishuKitModule 失败"
grep -q "extends io.github.libxposed.api.XposedModule" "$WORK/javap.txt" || fail "FeishuKitModule 未继承 XposedModule"
grep -q "public final class" "$WORK/javap.txt" || fail "入口应为 final"
echo "断言2 OK：FeishuKitModule extends XposedModule（final）"

# ── C. 源码无 legacy lifecycle 接口（代码级；javadoc 对照字样属阶段 6 拍板项，不在此列） ──
grep -rn "implements.*IXposedHook" "$APP" && fail "仍有类实现 legacy lifecycle 接口"
grep -rnE "(void|public|private|protected)[^/]*\binitZygote\s*\(" "$APP" && fail "仍有 initZygote"
grep -rn "handleLoadPackage\s*(" "$APP" && fail "仍有 handleLoadPackage"
grep -rn "LoadPackageParam" "$APP" && fail "仍有 LoadPackageParam 引用"
[ -f "$APP/com/chekayo/feishuantirecall/FuckLarkSettingsHook.java" ] && fail "FuckLarkSettingsHook 包装入口未删除"
grep -rln "IXposedHookZygoteInit\|IXposedHookLoadPackage" "$REPO/stubs" >/dev/null || fail "stubs 目录应保留 legacy 接口定义（阶段 4 才删）"
echo "断言3 OK：app 源码零 legacy lifecycle 接口（stubs 按计划保留至阶段 4）"

# ── D. 元数据：java_init.list 单入口指向 FeishuKitModule ──
[ "$(tr -d '\r' < "$RES/java_init.list" | grep -c .)" = "1" ] || fail "java_init.list 应恰好一行"
[ "$(tr -d '\r' < "$RES/java_init.list" | tr -d '\n')" = "com.chekayo.feishuantirecall.FeishuKitModule" ] \
  || fail "java_init.list 未指向 FeishuKitModule"
grep -q "^minApiVersion=102" "$RES/module.prop" || fail "module.prop minApiVersion != 102"
grep -q "^targetApiVersion=102" "$RES/module.prop" || fail "module.prop targetApiVersion != 102"
echo "断言4 OK：java_init.list 单入口 = FeishuKitModule，API 102 元数据在位"

echo "== PASS：阶段3 入口独立编译门 =="
