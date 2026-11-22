#!/bin/bash
set -e
A=~/workspace/android
SDK=$A/sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$A/jdk-17.0.11+9
export PATH=$JAVA_HOME/bin:$PATH
proj=dhamma
src=$A/projects/$proj/app/src/main
b=$A/build/$proj
rm -rf $b && mkdir -p $b/gen $b/obj $b/dex
[ -d $src/assets ] || mkdir -p $src/assets
echo "[$proj] aapt2 compile+link..."
$BT/aapt2 compile --dir $src/res -o $b/res.zip
$BT/aapt2 link --auto-add-overlay -o $b/unsigned.apk -I $AJAR --manifest $src/AndroidManifest.xml -A $src/assets -R $b/res.zip --java $b/gen
echo "[$proj] javac..."
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp $AJAR -d $b/obj $(find $src/java $b/gen -name "*.java")
echo "[$proj] d8..."
$BT/d8 --lib $AJAR --min-api 24 --output $b/dex $(find $b/obj -name "*.class")
# v13.11: pipeline F1 — a codeless ~6KB stub dex once passed
# apksigner verify + badging (v1.56) and was caught only by the
# run's independent check. Refuse to sign a stub here, in the
# script, before any packaging happens.
dex_bytes=$(stat -c%s $b/dex/classes.dex)
if [ "$dex_bytes" -lt 100000 ]; then echo "[$proj] FATAL: dex only $dex_bytes bytes — refusing to sign a stub" >&2; exit 1; fi
(cd $b && zip -q -j unsigned.apk dex/classes.dex)
echo "[$proj] zipalign + sign..."
$BT/zipalign -f -p 4 $b/unsigned.apk $b/aligned.apk >/dev/null
$BT/apksigner sign --ks $A/debug.keystore --ks-pass pass:android --key-pass pass:android --out $A/dhamma.apk $b/aligned.apk
$BT/apksigner verify $A/dhamma.apk && echo "[$proj] verified OK"
ls -la $A/dhamma.apk
