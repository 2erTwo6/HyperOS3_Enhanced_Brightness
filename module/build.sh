#!/bin/sh
# Build HyperOS-Enhanced-Brightness.apk. Needs: JDK 17 (javac/keytool), python3.
# r8.jar (D8 dexer, pure java, any arch) is fetched automatically.
set -e
cd "$(dirname "$0")"
if [ ! -s r8.jar ] || ! unzip -tq r8.jar >/dev/null 2>&1; then
    R8V=$(curl -s https://dl.google.com/android/maven2/com/android/tools/r8/maven-metadata.xml \
          | grep -o '<latest>[^<]*' | head -1 | sed 's/<latest>//')
    [ -n "$R8V" ] || R8V=8.5.35
    echo "fetching r8 $R8V ..."
    curl -sL -o r8.jar "https://dl.google.com/android/maven2/com/android/tools/r8/$R8V/r8-$R8V.jar" \
        && unzip -tq r8.jar > /dev/null || { echo "r8 download failed"; exit 1; }
fi
if [ ! -s android.jar ]; then
    echo "fetching official android.jar (compile classpath) ..."
    ZIP=$(curl -s https://dl.google.com/android/repository/repository2-3.xml \
          | grep -o 'platform-3[0-9]_r[0-9]*\.zip' | head -1)
    [ -n "$ZIP" ] || { echo "platform zip lookup failed"; exit 1; }
    curl -sL -o platform.zip "https://dl.google.com/android/repository/$ZIP" \
        || { echo "platform.zip download failed"; exit 1; }
    AJ=$(unzip -l platform.zip | awk '/\/android\.jar$/ {print $4; exit}')
    [ -n "$AJ" ] || { echo "android.jar not found in $ZIP"; exit 1; }
    unzip -p platform.zip "$AJ" > android.jar
    rm -f platform.zip
    [ -s android.jar ] || { echo "android.jar extract failed"; exit 1; }
fi
mkdir -p classes dexout
javac --release 8 -cp android.jar -d classes $(find src -name '*.java')
java -cp r8.jar com.android.tools.r8.D8 --release --min-api 29 --lib android.jar --output dexout classes/hbr/*.class
python3 build_apk.py