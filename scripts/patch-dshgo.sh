#!/usr/bin/env bash
# 一键给 dsh-Go 打补丁：取包 -> 反编译 -> 改清单 -> 重打包 -> 签名 -> 覆盖安装 -> 验证
#
# 用法：
#   bash scripts/patch-dshgo.sh                 # 全流程
#   bash scripts/patch-dshgo.sh --no-install    # 只产包，不安装
#
# 前置：apktool.jar、ks.jks 在仓库根目录；设备已启用 CorePatch（免签名校验）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PKG="${PKG:-io.github.ccch1mneyyy.dsh_app}"
DEVICE="${DEVICE:-192.168.31.244:6666}"
SDK="${SDK:-/c/Users/34983/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/37.0.0"
KEYSTORE="${KEYSTORE:-$ROOT/lspmod/ks.jks}"
KS_PASS="${KS_PASS:-android}"
APKTOOL="${APKTOOL:-$ROOT/apktool.jar}"
WORK="$ROOT/build-patch"
DO_INSTALL=1
[ "${1:-}" = "--no-install" ] && DO_INSTALL=0

win() { cygpath -w "$1"; }

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '\n!! %s\n' "$*" >&2; exit 1; }

# ---------- 0. 环境检查 ----------
say "检查环境"
[ -f "$APKTOOL" ] || die "缺少 apktool.jar（放到仓库根目录或设 APKTOOL=/path/to/apktool.jar）"
if [ ! -f "$KEYSTORE" ]; then
  say "生成签名用 keystore（$KEYSTORE）"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$(win "$KEYSTORE")" -alias dsh -keyalg RSA -keysize 2048     -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=DSH Toolbox" >/dev/null 2>&1     || die "keytool 生成 keystore 失败"
fi
[ -x "$BT/aapt2.exe" ] || die "找不到 build-tools：$BT"
command -v java >/dev/null || die "找不到 java"
command -v adb >/dev/null || die "找不到 adb"

if ! adb devices | grep -q "$DEVICE[[:space:]]*device"; then
  say "连接设备 $DEVICE"
  adb connect "$DEVICE" >/dev/null 2>&1 || true
  sleep 2
fi
adb -s "$DEVICE" get-state >/dev/null 2>&1 || die "设备未连接：$DEVICE（打开无线调试或插 USB）"

rm -rf "$WORK"
mkdir -p "$WORK"

# ---------- 1. 取当前安装包 ----------
say "拉取已安装的 APK"
APK_PATH=$(adb -s "$DEVICE" shell pm path "$PKG" | tr -d '\r' | sed 's/^package://' | head -1)
[ -n "$APK_PATH" ] || die "设备上没装 $PKG"
adb -s "$DEVICE" pull "$APK_PATH" "$(win "$WORK/base.apk")" >/dev/null
BUILT_VER=$("$BT/aapt2.exe" dump badging "$(win "$WORK/base.apk")" 2>/dev/null | head -1 || true)
echo "  已安装版本：$(echo "$BUILT_VER" | grep -o "versionName='[^']*'" | head -1) $(echo "$BUILT_VER" | grep -o "versionCode='[0-9]*'" | head -1)"

# ---------- 2. 反编译（只解资源） ----------
say "反编译（-s，跳过 dex）"
java -jar "$(win "$APKTOOL")" d -s -f -o "$(win "$WORK/decoded")" "$(win "$WORK/base.apk")" >/dev/null
echo "  ok"

# ---------- 3. 改清单 ----------
say "修改 AndroidManifest.xml"
python "$(win "$ROOT/scripts/patch_manifest.py")" "$(win "$WORK/decoded")"

# ---------- 4. 重打包 ----------
say "重打包"
java -jar "$(win "$APKTOOL")" b "$(win "$WORK/decoded")" -o "$(win "$WORK/patched-unsigned.apk")" >/dev/null
echo "  ok"

# ---------- 5. 对齐 + 签名 ----------
say "对齐 + 签名"
"$BT/zipalign.exe" -f 4 "$(cygpath -w "$WORK/patched-unsigned.apk")" "$(cygpath -w "$WORK/patched-aligned.apk")"
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KEYSTORE")" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --out "$(cygpath -w "$WORK/dshgo-patched.apk")" \
  "$(cygpath -w "$WORK/patched-aligned.apk")"
echo "  产物：$WORK/dshgo-patched.apk"

# 兜底：留一份在仓库根目录
cp "$WORK/dshgo-patched.apk" "$ROOT/dshgo_patched.apk"

if [ "$DO_INSTALL" -eq 0 ]; then
  say "完成（未安装）"
  exit 0
fi

# ---------- 6. 覆盖安装 ----------
say "覆盖安装（签名不同由 CorePatch 放行）"
if ! OUT=$(adb -s "$DEVICE" install -r "$(cygpath -w "$WORK/dshgo-patched.apk")" 2>&1); then
  echo "$OUT"
  case "$OUT" in
    *UPDATE_INCOMPATIBLE*|*signatures*do*not*match*)
      die "签名校验没被绕过：确认 CorePatch 已启用（模块启用 + 作用域 system + 重启），并且开机日志没有 'Unsupported Version'。";;
    *VERSION_DOWNGRADE*)
      die "版本降级：拉到的包比已装版本旧（本脚本已从设备取包，出现此错说明中途被替换）。";;
    *) die "安装失败，见上面的输出。";;
  esac
fi
echo "  ok"

# ---------- 7. 验证 ----------
say "验证"
adb -s "$DEVICE" shell "dumpsys package $PKG | grep -m1 versionName"
adb -s "$DEVICE" shell "dumpsys activity services $PKG 2>/dev/null | grep -o 'types=0x[0-9a-f]*'" 2>/dev/null | head -1 || true
cat <<'EOF'

说明：
  - 期望看到 types=0x40000080（specialUse | microphone）；若 App 尚未启动则为空，属正常。
  - 清单允许 ≠ 运行时带上，模块里已 hook startForeground 补 0x80 位。
  - 以后官方更新后需重跑本脚本（签名不同，不能直接用官方包覆盖）。
EOF
