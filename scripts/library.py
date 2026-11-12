#!/usr/bin/env python3
"""The official plugin library: catalogue, signature and GitHub releases.

    python3 -I scripts/library.py build      # library/library.json from the built APKs, signed
    python3 -I scripts/library.py publish    # releases for new plugin versions, then the catalogue
    python3 -I scripts/library.py check <id> # a plugin's declaration (build-plugin.sh runs it)

Build every plugin first (scripts/build-plugin.sh <id>). The catalogue lists each plugin's newest
build: its version, size, sha256 and signing certificate, read from the APK itself. `seq` goes up
by one on every build, so a TV never accepts an older catalogue.

The library key (ECDSA P-256, PEM) stays outside the repository: HEARTH_LIBRARY_KEY, by default
../hearth/keystore/hearth-library.pem. Needs openssl, the Android build tools and gh.
"""
import base64
import datetime
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "zuptalo/hearth-plugins"
TAG = "library"
LIBRARY = {"id": "hearth-official", "name": "Hearth TV", "publisher": "Zuptalo"}
PREFIX = "dev.hearth.plugin."
SDK = os.environ.get("ANDROID_HOME", "/usr/local/share/android-commandlinetools")
BT = os.path.join(SDK, "build-tools", "34.0.0")
KEY = os.environ.get("HEARTH_LIBRARY_KEY", os.path.join(ROOT, "..", "hearth", "keystore", "hearth-library.pem"))
NS = "{http://hearth.dev/plugin}"
OUT = os.path.join(ROOT, "library", "library.json")


def run(*cmd, data=None):
    return subprocess.run(cmd, check=True, capture_output=True, input=data).stdout


def public_key_der():
    return run("openssl", "ec", "-in", KEY, "-pubout", "-outform", "DER")


def apk_facts(apk):
    badging = run(os.path.join(BT, "aapt2"), "dump", "badging", apk).decode()
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    certs = run(os.path.join(BT, "apksigner"), "verify", "--print-certs", apk).decode()
    signers = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs)
    if len(set(signers)) != 1:
        sys.exit(f"{apk}: expected exactly one signer, found {len(set(signers))}")
    with open(apk, "rb") as f:
        body = f.read()
    return {"package": m.group(1), "versionCode": int(m.group(2)), "version": m.group(3),
            "size": len(body), "sha256": hashlib.sha256(body).hexdigest(), "signer": signers[0]}


# Remote buttons (contract §3.7). Keys a plugin may suggest, as Android names them without KEYCODE_.
SCOPES = {"everywhere", "home", "details", "player", "youtube", "music"}
KEYS = {"PROG_RED", "PROG_GREEN", "PROG_YELLOW", "PROG_BLUE", "CHANNEL_UP", "CHANNEL_DOWN", "GUIDE", "CAPTIONS",
        "MENU", "TV_TELETEXT", "LAST_CHANNEL", "MEDIA_RECORD", "BOOKMARK"} | {str(d) for d in range(10)}


def actions(pid, x):
    """The plugin's <action> elements, checked: every rule here is one Hearth TV relies on."""
    out, problems = [], []
    for a in x.findall("action"):
        g = lambda k: (a.get(NS + k) or "").strip()
        aid, label, scope, key = g("id"), g("label"), g("scope"), g("key")
        alts = [k.strip() for k in g("alternatives").split(",") if k.strip()]
        where = f"{pid}: action {aid or '?'}"
        if not re.fullmatch(r"[a-z0-9_.]{1,40}", aid): problems.append(f"{where}: id must be [a-z0-9_.], up to 40")
        if not label or len(label) > 40: problems.append(f"{where}: label must be 1-40 characters, said for people")
        if scope not in SCOPES: problems.append(f"{where}: scope must be one of {sorted(SCOPES)}")
        if key not in KEYS: problems.append(f"{where}: key {key!r} isn't one a plugin may suggest ({sorted(KEYS)})")
        if len(alts) < 2: problems.append(f"{where}: give at least two alternatives, best first, for when {key} is taken")
        for k in alts:
            if k not in KEYS or k == key: problems.append(f"{where}: alternative {k!r} isn't allowed (or repeats the key)")
        out.append({"id": aid, "label": label, "scope": scope, "key": key, "alternatives": alts})
    if len(out) > 8: problems.append(f"{pid}: at most 8 actions")
    if len({a["id"] for a in out}) != len(out): problems.append(f"{pid}: action ids must be unique")
    if out and "actions" not in [p.strip() for p in (x.get(NS + "provides") or "").split(",")]:
        problems.append(f"{pid}: has actions, so provides must include \"actions\" (and answer actions.run)")
    if problems:
        sys.exit("\n".join(problems))
    return out


def plugin_facts(pid):
    x = ET.parse(os.path.join(ROOT, "plugins", pid, "res", "xml", "hearth_plugin.xml")).getroot()
    a = lambda k, d="": x.get(NS + k, d)
    with open(os.path.join(ROOT, "plugins", pid, "plugin.json")) as f:
        extra = json.load(f)
    split = lambda s: [p.strip() for p in s.split(",") if p.strip()]
    return {"id": a("id"), "name": a("name"), "summary": extra["summary"], "api": int(a("api", "1")),
            "minHost": int(a("minHost", "1")), "color": a("color"), "provides": split(a("provides")),
            "capabilities": split(a("capabilities")), "ratesContent": a("ratesContent") == "true",
            "actions": actions(pid, x)}


def build():
    old = {}
    if os.path.exists(OUT):
        with open(OUT) as f:
            old = json.load(f)
    plugins = []
    for apk in sorted(glob.glob(os.path.join(ROOT, "plugins", "*", "build", "*.apk"))):
        pid = os.path.basename(os.path.dirname(os.path.dirname(apk)))
        if os.path.basename(apk) != pid + ".apk":
            continue
        p = plugin_facts(pid)
        a = apk_facts(apk)
        if p["id"] != pid or a["package"] != PREFIX + pid:
            sys.exit(f"{pid}: id and package must be {pid} and {PREFIX}{pid}")
        rel = f"{pid}-{a['version']}"
        p.update(a)
        p["url"] = f"https://github.com/{REPO}/releases/download/{rel}/{rel}.apk"
        if os.path.exists(os.path.join(ROOT, "plugins", pid, "logo.png")):
            p["logo"] = f"https://github.com/{REPO}/releases/download/{rel}/{pid}.png"
        plugins.append(p)
    der = public_key_der()
    key = "ec-p256:" + hashlib.sha256(der).hexdigest()
    lib = dict(LIBRARY, key=key, publicKey=base64.b64encode(der).decode(), prefix=PREFIX)
    doc = {"library": lib, "seq": old.get("seq", 0) + 1,
           "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "plugins": plugins}
    body = (json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode()
    with open(OUT, "wb") as f:
        f.write(body)
    sig = run("openssl", "dgst", "-sha256", "-sign", KEY, data=body)
    with open(OUT + ".sig", "wb") as f:
        f.write(base64.b64encode(sig) + b"\n")
    # Check the signature as a TV would, with the public key alone.
    pub = os.path.join(ROOT, "library", ".pub.pem")
    with open(pub, "wb") as f:
        f.write(run("openssl", "ec", "-in", KEY, "-pubout"))
    try:
        run("openssl", "dgst", "-sha256", "-verify", pub, "-signature", "/dev/stdin", OUT, data=sig)
    finally:
        os.remove(pub)
    print(f"library.json seq {doc['seq']}: {', '.join(p['id'] + ' ' + p['version'] for p in plugins)}; signed and verified")


def released(tag):
    return subprocess.run(["gh", "release", "view", tag, "-R", REPO], capture_output=True).returncode == 0


def publish():
    with open(OUT) as f:
        doc = json.load(f)
    for p in doc["plugins"]:
        rel = f"{p['id']}-{p['version']}"
        if released(rel):
            continue
        apk = os.path.join(ROOT, "plugins", p["id"], "build", p["id"] + ".apk")
        if apk_facts(apk)["sha256"] != p["sha256"]:
            sys.exit(f"{p['id']}: the built APK isn't the one in the catalogue; run build again")
        named = os.path.join(ROOT, "plugins", p["id"], "build", rel + ".apk")
        run("cp", apk, named)
        files = [named]
        logo = os.path.join(ROOT, "plugins", p["id"], "logo.png")
        if "logo" in p:
            run("cp", logo, os.path.join(ROOT, "plugins", p["id"], "build", p["id"] + ".png"))
            files.append(os.path.join(ROOT, "plugins", p["id"], "build", p["id"] + ".png"))
        run("gh", "release", "create", rel, "-R", REPO, "--title", f"{p['name']} {p['version']}",
            "--notes", f"{p['name']} plugin {p['version']} for Hearth TV.", *files)
        print("Released", rel)
    if not released(TAG):
        run("gh", "release", "create", TAG, "-R", REPO, "--title", "Plugin library",
            "--notes", "The official Hearth TV plugin library: library.json and its signature.", "--latest=false")
    run("gh", "release", "upload", TAG, "-R", REPO, "--clobber", OUT, OUT + ".sig")
    print(f"Published library seq {doc['seq']}")


def check():
    """python3 -I scripts/library.py check <id>: the plugin's declaration, as build-plugin.sh runs it."""
    if len(sys.argv) < 3:
        sys.exit("check needs a plugin id")
    plugin_facts(sys.argv[2])
    print(f"{sys.argv[2]}: declaration OK")


if __name__ == "__main__":
    {"build": build, "publish": publish, "check": check}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
