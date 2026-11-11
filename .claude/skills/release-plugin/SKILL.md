---
name: release-plugin
description: Release a Hearth TV plugin to a library: build, sign with the publisher key, hash, update and sign library.json, and publish as a GitHub release. Use when shipping a plugin version.
---

# Releasing a plugin

Signing keys never enter the repository. The APK key and the library key (ECDSA P-256) live with
the maintainer (or in CI secrets) and are passed in by path or environment variable.

1. Bump `versionCode` and `versionName` in the plugin's manifest.
2. Build and sign the APK with the publisher key. Record the certificate's SHA-256
   (`apksigner verify --print-certs`). It must stay the same for every release of that plugin.
3. Compute the APK's sha256 and size.
4. Update the plugin's entry in `library/library.json`:
   - version, versionCode, url, sha256, size and signer;
   - increase `seq`;
   - set `generated`.
5. Sign `library.json`:
   `openssl dgst -sha256 -sign <library-key.pem> -out library.json.sig library.json`, then base64.
   Verify with the public key before publishing.
6. Create a GitHub release `<id>-<version>` with the APK attached. Upload `library.json` and
   `library.json.sig` to the library release.
7. Check on a TV: Settings → Plugins shows the update, and installing verifies.
