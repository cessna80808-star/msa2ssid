#!/usr/bin/env python3
"""Fetches the compile classpath for one Minecraft version: the client jar, every
Mojang library it needs, Fabric Loader and the Fabric API module jars.

Prints the classpath on stdout and caches everything under <outdir>/<version>.
"""
import json
import os
import sys
import urllib.request
import zipfile

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
LOADER_VERSION = "0.20.1"
LOADER_URL = f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{LOADER_VERSION}/fabric-loader-{LOADER_VERSION}.jar"
MODRINTH = "https://api.modrinth.com/v2/project/fabric-api/version"
UA = {"User-Agent": "msa2ssid-build/1.0"}


def get(url, dest=None):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if dest:
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if not os.path.exists(dest):
            with open(dest, "wb") as f:
                f.write(data)
        return dest
    return data


def fetch_json(url):
    return json.loads(get(url))


def main():
    version = sys.argv[1]
    out = os.path.join(sys.argv[2], version)
    os.makedirs(out, exist_ok=True)
    cp = []

    manifest = fetch_json(MANIFEST)
    entry = next(v for v in manifest["versions"] if v["id"] == version)
    meta = fetch_json(entry["url"])

    client_url = meta["downloads"]["client"]["url"]
    cp.append(get(client_url, os.path.join(out, "client.jar")))

    libs = os.path.join(out, "libs")
    for lib in meta.get("libraries", []):
        artifact = (lib.get("downloads") or {}).get("artifact")
        if not artifact or not artifact.get("url"):
            continue
        name = os.path.basename(artifact["path"])
        try:
            cp.append(get(artifact["url"], os.path.join(libs, name)))
        except Exception as e:  # a missing optional library must not fail the build
            print(f"warn: {name}: {e}", file=sys.stderr)

    cp.append(get(LOADER_URL, os.path.join(out, f"fabric-loader-{LOADER_VERSION}.jar")))

    api_jar = os.path.join(out, "fabric-api.jar")
    if not os.path.exists(api_jar):
        query = urllib.parse.urlencode({"game_versions": json.dumps([version])})
        versions = fetch_json(f"{MODRINTH}?{query}")
        files = versions[0]["files"]
        primary = next(item for item in files if item["primary"])
        get(primary["url"], api_jar)

    modules = os.path.join(out, "fabric-api-modules")
    if not os.path.isdir(modules) or not os.listdir(modules):
        os.makedirs(modules, exist_ok=True)
        with zipfile.ZipFile(api_jar) as zf:
            for name in zf.namelist():
                if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                    with open(os.path.join(modules, os.path.basename(name)), "wb") as f:
                        f.write(zf.read(name))
    cp.extend(os.path.join(modules, n) for n in sorted(os.listdir(modules)) if n.endswith(".jar"))

    print(os.pathsep.join(cp))


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (used above via closure)
    main()
