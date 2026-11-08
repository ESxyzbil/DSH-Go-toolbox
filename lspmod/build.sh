#!/usr/bin/env bash
set -e
SDK="/c/Users/34983/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/37.0.0"
AJAR="$SDK/platforms/android-37.0/android.jar"
cd /c/Users/34983/default/lspmod
rm -rf out dex build
mkdir -p out dex build/dexout

echo "== javac"
find src -name "*.java" > sources.txt
if ! javac -encoding UTF-8 -source 8 -target 8 -nowarn \
     -cp "$(cygpath -w "$AJAR")" -d out @sources.txt > javac.log 2>&1; then
  echo "!! javac failed:"
  iconv -f GBK -t UTF-8 javac.log 2>/dev/null | grep -a -A4 "错误\|error:" | head -40 || cat javac.log
  exit 1
fi
echo "   ok"

echo "== collect module classes only"
cp -r out/com dex/

echo "== d8"
find dex -name "*.class" > classes.txt
"$BT/d8.bat" --min-api 26 --output "$(cygpath -w build/dexout)" $(cat classes.txt) 2>&1 | tail -3
ls -l build/dexout

echo "== aapt2 compile res"
"$BT/aapt2.exe" compile --dir "$(cygpath -w res)" -o "$(cygpath -w build/res.zip)" 2>&1 | tail -3

echo "== aapt2 link"
"$BT/aapt2.exe" link --manifest "$(cygpath -w AndroidManifest.xml)" \
  -I "$(cygpath -w "$AJAR")" "$(cygpath -w build/res.zip)" \
  -o "$(cygpath -w build/base.apk)" 2>&1 | tail -3
ls -l build/base.apk

echo "== add dex + xposed_init"
python addzip.py build/base.apk build/dexout/classes.dex classes.dex
python addzip.py build/base.apk assets/xposed_init assets/xposed_init
python addzip.py build/base.apk assets/ptt_daemon.sh assets/ptt_daemon.sh

echo "== zipalign"
"$BT/zipalign.exe" -f 4 "$(cygpath -w build/base.apk)" "$(cygpath -w build/aligned.apk)"

echo "== keystore + sign"
if [ ! -f ks.jks ]; then
  keytool -genkeypair -keystore ks.jks -alias dsh -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android -dname "CN=dshfix" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$(cygpath -w ks.jks)" --ks-pass pass:android --key-pass pass:android \
  --out "$(cygpath -w build/dshfix.apk)" "$(cygpath -w build/aligned.apk)" 2>&1 | tail -3
"$BT/apksigner.bat" verify "$(cygpath -w build/dshfix.apk)" 2>&1 | tail -3
ls -l build/dshfix.apk
