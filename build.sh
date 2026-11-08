#!/usr/bin/env bash
# Build a signed WebView APK without Gradle / Android Studio.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
OUT="$HERE/build"
# 版本号唯一来源：改这两行即可，文件名与 APK 内的 versionName 都跟着走。
VER_CODE=57
VER_NAME="1.9.7"
APK="$OUT/幻豆_${VER_NAME}.apk"

# 签名材料不进仓库：keystore 路径与密码都从环境变量取。
# 首次构建时若 keystore 不存在，脚本会按下面的 dname 现场生成一个。
KS="${HUANDOU_KS:-$HERE/keystore/release.jks}"
STORE_PASS="${HUANDOU_KS_PASS:?请先执行 export HUANDOU_KS_PASS=<你的签名密码>}"

mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"
rm -rf "$OUT/gen"/* "$OUT/obj"/* "$OUT/dex"/*
rm -f "$OUT/compiled.zip" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/app-release.apk"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/compiled.zip"

echo "[2/7] aapt2 link -> base.apk + R.java"
"$BT/aapt2" link -I "$AJ" \
  --manifest "$HERE/AndroidManifest.xml" \
  -o "$OUT/base.apk" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" \
  --auto-add-overlay "$OUT/compiled.zip"

echo "[3/7] javac (Java 8 bytecode, UTF-8 sources)"
# 先核对 KeyAttest 里的 OID 常量。写错一个字节不会编译失败，
# 只会让「有没有认证扩展」永远返回 false —— 那种假阴性没法从日志里看出来。
python3 "$HERE/tools/check_oid.py" || { echo "OID 常量校验失败，中止打包"; exit 1; }
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -encoding UTF-8 -classpath "$AJ" -d "$OUT/obj" @"$OUT/sources.txt"

echo "[4/7] R8 Full Mode -> classes.dex"
# 用 cmdline-tools 自带的 r8.jar，不依赖 Gradle。--release 就是 Full Mode：
# 允许类合并、成员裁剪、访问修饰符改写。keep 规则见 proguard-rules.pro。
# R8 不直接吃 class 目录，先把 build/obj 打成 jar 再交给它。
R8_JAR="$SDK/cmdline-tools/latest/lib/r8.jar"
rm -rf "$OUT/dex" "$OUT/program.jar"; mkdir -p "$OUT/dex"
(cd "$OUT/obj" && jar cf "$OUT/program.jar" .)
java -Xmx2g -cp "$R8_JAR" com.android.tools.r8.R8 \
  --release \
  --min-api 24 \
  --lib "$AJ" \
  --pg-conf "$HERE/proguard-rules.pro" \
  --pg-map-output "$OUT/mapping.txt" \
  --output "$OUT/dex" \
  "$OUT/program.jar"

echo "[5/7] append classes.dex into APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)

echo "[6/7] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] create keystore (if needed) + apksigner"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -v -keystore "$KS" -alias huandou \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=Huandou, OU=Personal, O=Huandou, L=Beijing, ST=Beijing, C=CN" \
    > /dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$STORE_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/app-release.apk" "$OUT/aligned.apk"

echo "[verify] apksigner verify"
"$BT/apksigner" verify --verbose "$OUT/app-release.apk"

cp "$OUT/app-release.apk" "$APK"
echo "DONE: $APK"
ls -lh "$APK"
