#!/usr/bin/env bash
# 飞书防撤回 Xposed 模块构建脚本 —— Linux 版（等价于 build.ps1）
# 自动探测 NDK / SDK / JDK / Keystore；clone 后装好这些工具即可一键构建。
#
# 覆盖探测的环境变量（可选）：
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK 根目录
#   ANDROID_NDK_HOME / NDK_HOME      Android NDK 根目录
#   JAVA_HOME                        JDK 根目录
#   ANDROID_JAR                      android.jar 路径
#   KEYSTORE                         签名 keystore 路径
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$PROJ/build"

die() { echo "ERROR: $*" >&2; exit 1; }

# ── 1. JDK（需要 11+，d8/apksigner 要求） ──────────────────────────
JDKHOME="${JAVA_HOME:-}"
if [[ -z "$JDKHOME" || ! -x "$JDKHOME/bin/javac" ]]; then
  command -v javac >/dev/null || die "JDK not found. Install JDK 11+ and set JAVA_HOME."
  JDKHOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
export JAVA_HOME="$JDKHOME"
JAVAC="$JDKHOME/bin/javac"
KEYTOOL="$JDKHOME/bin/keytool"
echo "JDK = $JDKHOME ($("$JAVAC" -version 2>&1))"

# ── 2. Android SDK ────────────────────────────────────────────────
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /opt/android-sdk; do
    [[ -d "$c" ]] && { SDK="$c"; break; }
  done
fi
[[ -n "$SDK" && -d "$SDK" ]] || die "Android SDK not found. Set ANDROID_HOME."
echo "SDK = $SDK"

# ── 3. Build-Tools（d8 / aapt2 / zipalign / apksigner） ───────────
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[[ -n "$BT" && -x "${BT}aapt2" ]] || die "build-tools not found under $SDK/build-tools"
BT="${BT%/}"
D8="$BT/d8"; AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
for t in "$D8" "$AAPT2" "$ZIPALIGN" "$APKSIGNER"; do
  [[ -x "$t" ]] || die "Required tool not found: $t"
done
echo "Build-Tools = $BT"

# ── 4. android.jar ────────────────────────────────────────────────
ANDROID_JAR="${ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" || ! -f "$ANDROID_JAR" ]]; then
  ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
[[ -n "$ANDROID_JAR" && -f "$ANDROID_JAR" ]] || die "android.jar not found. Install an SDK platform."
echo "android.jar = $ANDROID_JAR"

# ── 5. NDK ────────────────────────────────────────────────────────
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  NDK="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  NDK="${NDK%/}"
fi
[[ -n "$NDK" && -d "$NDK" ]] || die "Android NDK not found. Set ANDROID_NDK_HOME."
HOSTTAG="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"   # linux-x86_64 / darwin-x86_64
CLANGPP="$NDK/toolchains/llvm/prebuilt/$HOSTTAG/bin/clang++"
[[ -x "$CLANGPP" ]] || die "clang++ not found at $CLANGPP"
echo "NDK = $NDK"
echo "clang++ = $CLANGPP"

# ── 6. Keystore ───────────────────────────────────────────────────
KS="${KEYSTORE:-}"
if [[ -z "$KS" || ! -f "$KS" ]]; then
  KS="$PROJ/debug.keystore"
  if [[ ! -f "$KS" ]]; then
    echo "Generating debug keystore at $KS ..."
    "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android \
      -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
      -validity 10000 -dname 'CN=Debug,O=Debug,C=US' >/dev/null 2>&1 || die "keytool failed"
  fi
fi
echo "Keystore = $KS"

# ── 构建开始 ──────────────────────────────────────────────────────
rm -rf "$BUILD"
mkdir -p "$BUILD/stubs" "$BUILD/app"

# libxposed API 102: 从 AAR 解出 classes.jar（仅编译期，不进模块 DEX）
XAPI_AAR="$PROJ/third_party/libxposed/api-102.0.0.aar"
[[ -f "$XAPI_AAR" ]] || die "libxposed API AAR not found: $XAPI_AAR (see third_party/libxposed/README.md)"
mkdir -p "$BUILD/libxposed"
( cd "$BUILD/libxposed" && "$JDKHOME/bin/jar" -xf "$XAPI_AAR" classes.jar )
[[ -f "$BUILD/libxposed/classes.jar" ]] || die "failed to extract classes.jar from $XAPI_AAR"

echo; echo "== 0. NDK compile libantirecall.so (arm64-v8a) =="
NJNI="$PROJ/native/jni"
"$CLANGPP" --target=aarch64-linux-android24 -fPIC -shared -O2 -fvisibility=hidden \
  -fno-rtti -fno-exceptions -mno-outline-atomics -static-libstdc++ \
  -Wl,-z,now -Wl,-z,relro -Wl,-z,noexecstack -Wl,--no-undefined \
  -Wl,--pack-dyn-relocs=none -Wl,--hash-style=both \
  -o "$BUILD/libantirecall.so" "$NJNI/antirecall.cpp" "$NJNI/And64InlineHook.cpp" \
  -llog -ldl -lm
echo "libantirecall.so = $(awk "BEGIN{printf \"%.1f\", $(stat -c%s "$BUILD/libantirecall.so")/1024}")KB"
# 额外编 libresign.so (离职统计)
"$CLANGPP" --target=aarch64-linux-android24 -fPIC -shared -O2 -fvisibility=hidden \
  -fno-rtti -fno-exceptions -mno-outline-atomics -static-libstdc++ \
  -Wl,-z,now -Wl,-z,relro -Wl,-z,noexecstack -Wl,--no-undefined \
  -Wl,--pack-dyn-relocs=none -Wl,--hash-style=both \
  -o "$BUILD/libresign.so" "$NJNI/resign.cpp" "$NJNI/And64InlineHook.cpp" \
  -llog -ldl -lm
echo "libresign.so = $(awk "BEGIN{printf \"%.1f\", $(stat -c%s "$BUILD/libresign.so")/1024}")KB"

echo; echo "== 1. javac stubs =="
# stubs/java/lang/invoke/LambdaMetafactory.java 是 -source 8 的 lambda 编译桩，与 Xposed 无关；
# 将来清理 legacy stubs(de/robv) 时必须保留它。
find "$PROJ/stubs" -name '*.java' > "$BUILD/stub.list"
"$JAVAC" --release 8 -d "$BUILD/stubs" @"$BUILD/stub.list"

echo; echo "== 2. javac module =="
find "$PROJ/app/src/main/java" -name '*.java' > "$BUILD/src.list"
"$JAVAC" -g -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$BUILD/stubs:$BUILD/libxposed/classes.jar" -d "$BUILD/app" @"$BUILD/src.list"

echo; echo "== 3. d8 -> classes.dex =="
# min-api 29: Java 8 lambda/invokedynamic 原生支持，无需 --no-desugaring。
# d8 输入只含 $BUILD/app 的类；libxposed classes.jar 仅编译期依赖，不得进入 DEX。
find "$BUILD/app" -name '*.class' > "$BUILD/cls.list"
"$D8" --min-api 29 --output "$BUILD" @"$BUILD/cls.list"

echo; echo "== 4. aapt2 compile + link =="
"$AAPT2" compile --dir "$PROJ/app/src/main/res" -o "$BUILD/res.zip"
"$AAPT2" link -o "$BUILD/app-unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  -A "$PROJ/app/src/main/assets" \
  --min-sdk-version 29 --target-sdk-version 34 \
  "$BUILD/res.zip"

echo; echo "== 5. 塞入 classes.dex + lib/arm64-v8a/*.so + META-INF/xposed =="
mkdir -p "$BUILD/stage/lib/arm64-v8a" "$BUILD/stage/META-INF/xposed"
cp "$BUILD/classes.dex" "$BUILD/stage/classes.dex"
cp "$BUILD/libantirecall.so" "$BUILD/stage/lib/arm64-v8a/libantirecall.so"
cp "$BUILD/libresign.so" "$BUILD/stage/lib/arm64-v8a/libresign.so"
# modern Xposed 元数据：追加到 APK 根目录 META-INF/xposed/（框架按此发现入口，不在 assets/）
RES="$PROJ/app/src/main/resources"
for f in java_init.list module.prop scope.list; do
  [[ -f "$RES/META-INF/xposed/$f" ]] || die "modern metadata missing: META-INF/xposed/$f"
  cp "$RES/META-INF/xposed/$f" "$BUILD/stage/META-INF/xposed/$f"
done
( cd "$BUILD/stage" && zip -q -X "$BUILD/app-unsigned.apk" classes.dex lib/arm64-v8a/libantirecall.so lib/arm64-v8a/libresign.so META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list )

echo; echo "== 6. zipalign =="
"$ZIPALIGN" -p -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo; echo "== 7. apksigner sign =="
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$PROJ/feishu-antirecall.apk" "$BUILD/app-aligned.apk"

echo; echo "== DONE =="
"$APKSIGNER" verify --print-certs "$PROJ/feishu-antirecall.apk" 2>&1 | head -3
echo "APK = $PROJ/feishu-antirecall.apk  size=$(awk "BEGIN{printf \"%.1f\", $(stat -c%s "$PROJ/feishu-antirecall.apk")/1024}")KB"
