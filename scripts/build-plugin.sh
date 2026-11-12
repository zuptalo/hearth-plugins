#!/bin/bash
# Builds and signs one plugin with plain SDK tools (no Gradle): bash scripts/build-plugin.sh weather
# Output: plugins/<id>/build/<id>.apk
#
# The SDK is compiled in from the hearth repository (HEARTH_SDK, default ../hearth/sdk/src).
# Signing: official plugins use the official plugin key, kept outside the repository. Contributors
# set HEARTH_PLUGIN_KEYSTORE / HEARTH_PLUGIN_KEY_ALIAS / HEARTH_PLUGIN_KEY_PASS_FILE to their own key.
set -euo pipefail
cd "$(dirname "$0")/.."
ID="${1:?usage: build-plugin.sh <plugin-id>}"
[[ "$ID" =~ ^[a-z0-9_]+$ ]] || { echo "Bad plugin id: $ID" >&2; exit 1; }
P="plugins/$ID"; [ -f "$P/AndroidManifest.xml" ] || { echo "No plugin at $P" >&2; exit 1; }

export JAVA_HOME="${HEARTH_JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/temurin-17.0.14/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/usr/local/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"; JAR="$SDK/platforms/android-34/android.jar"; D8="$SDK/build-tools/35.0.0/d8"
HEARTH_SDK="${HEARTH_SDK:-../hearth/sdk/src}"
[ -d "$HEARTH_SDK/dev/hearth/sdk" ] || { echo "Hearth SDK not found at $HEARTH_SDK (set HEARTH_SDK)" >&2; exit 1; }
KS="${HEARTH_PLUGIN_KEYSTORE:-../hearth/keystore/hearth-plugins.keystore}"
ALIAS="${HEARTH_PLUGIN_KEY_ALIAS:-hearthplugins}"
PW_FILE="${HEARTH_PLUGIN_KEY_PASS_FILE:-../hearth/keystore/hearth-plugins-password.txt}"
[ -f "$KS" ] || { echo "No signing key at $KS. Make your own with keytool and set HEARTH_PLUGIN_KEYSTORE." >&2; exit 1; }
VERSION_CODE="${VERSION_CODE:-1}"; VERSION_NAME="${VERSION_NAME:-1.0.0}"

# The declaration first (id, provides, buttons): the rules Hearth TV relies on, contract §2 and §3.7.
python3 -I "$(dirname "$0")/library.py" check "$(basename "$P")"

B="$P/build"; rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir "$P/res" -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest "$P/AndroidManifest.xml" --java "$B/gen" \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$B/res.zip"
javac -encoding UTF-8 --release 11 -Xlint:-options -classpath "$JAR" -d "$B/classes" \
    $(find "$P/src" "$HEARTH_SDK" "$B/gen" -name '*.java')
"$D8" --release --min-api 24 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
(cd "$B/dex" && zip -q ../unsigned.apk classes*.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass file:"$PW_FILE" --ks-key-alias "$ALIAS" --out "$B/$ID.apk" "$B/aligned.apk"
rm -f "$B/$ID.apk.idsig"
echo "Built $B/$ID.apk ($(du -h "$B/$ID.apk" | cut -f1)), signer sha256 $("$BT/apksigner" verify --print-certs "$B/$ID.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
