#!/bin/bash
# FamilyGuardHelper 手工构建流水线
# 用法: bash scripts/build_apk.sh [版本号]
# 依赖: JDK 11+, Android SDK (aapt2/d8/zipalign/apksigner), Python 3
# 环境变量: ANDROID_SDK (默认 %LOCALAPPDATA%/Android/Sdk)
set -e

VER="${1:-1.0}"
SDK="${ANDROID_SDK:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"

REPO="$(cd "$(dirname "$0")/.." && pwd)"
B="$REPO/build-apk"
SRC="$REPO/app"

# oplus dex 检查
if [ ! -f "$SRC/assets/fg_oplus.dex" ]; then
    echo "!! 缺少 app/assets/fg_oplus.dex (OPPO 专有组件)"
    echo "   请按 docs/extract_oplus_dex.md 从 ColorOS 固件提取后放入"
    exit 1
fi

rm -rf "$B/classes" "$B/dex" "$B/base.apk" "$B/unsigned.apk" "$B/aligned.apk"
mkdir -p "$B/classes" "$B/dex"

echo "[1/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$B/res.zip"

echo "[2/7] aapt2 link"
"$BT/aapt2.exe" link -I "$AJ" --manifest "$SRC/AndroidManifest.xml" \
    -A "$SRC/assets" --min-sdk-version 26 --target-sdk-version 34 \
    -o "$B/base.apk" "$B/res.zip"

echo "[3/7] javac"
javac -encoding UTF-8 --release 11 -classpath "$AJ;$REPO/stubs" -d "$B/classes" \
    "$REPO/stubs/de/robv/android/xposed/"*.java \
    "$REPO/stubs/de/robv/android/xposed/callbacks/"*.java \
    "$SRC/java/com/sb/familyguardhelper/"*.java

echo "[4/7] d8"
"$BT/d8.bat" --release --min-api 26 --lib "$AJ" --output "$B/dex" \
    $(find "$B/classes/com/sb" -name '*.class' | tr '\n' ' ')

echo "[5/7] merge dex into apk"
python - "$B" << 'PYEOF'
import sys, zipfile
b = sys.argv[1]
src = zipfile.ZipFile(b + "/base.apk")
out = zipfile.ZipFile(b + "/unsigned.apk", "w")
for info in src.infolist():
    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    out.writestr(zi, src.read(info.filename))
out.writestr("classes.dex", open(b + "/dex/classes.dex", "rb").read(),
             compress_type=zipfile.ZIP_DEFLATED)
out.close(); src.close()
print("merge OK")
PYEOF

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[7/7] apksigner"
KEYSTORE="${KEYSTORE:-$REPO/scripts/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out "$B/FamilyGuardHelper-v$VER.apk" "$B/aligned.apk"

echo ""
echo "完成: $B/FamilyGuardHelper-v$VER.apk"
echo "安装: adb install -r \"$B/FamilyGuardHelper-v$VER.apk\""
