---
name: release-plugin
description: Release a Hearth TV plugin to a library: build, sign with the publisher key, hash, update and sign library.json, and publish as a GitHub release. Use when shipping a plugin version.
---

# Releasing a plugin

Signing keys never enter the repository. The APK key and the library key (ECDSA P-256) live with
the maintainer (or in CI secrets) and are passed in by path or environment variable.

1. Build with a higher version: `VERSION_CODE=<n> VERSION_NAME=<x.y.z> bash scripts/build-plugin.sh <id>`.
   The signer printed must stay the same for every release of that plugin.
2. `python3 -I scripts/library.py build`: writes `library/library.json` from the built APKs
   (version, size, sha256, signer), raises `seq`, signs it and verifies the signature.
3. `python3 -I scripts/library.py publish`: makes a release `<id>-<version>` with the APK and logo
   for each new version, then uploads `library.json` and its signature to the `library` release.
4. Commit `library/library.json` and `library.json.sig`.
5. Check on a TV: Settings → Plugins shows the update, and installing verifies.

The library key path is `HEARTH_LIBRARY_KEY` (default `../hearth/keystore/hearth-library.pem`).
