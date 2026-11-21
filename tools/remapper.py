#!/usr/bin/env python3
"""Mappings support for the Minecraft versions that ship an obfuscated client jar.

26.x ships unobfuscated, so the mod is compiled straight against the client jar and
the classes it produces already use the names the game has at runtime. 1.21.x and
older ship obfuscated, so those builds need two extra steps:

  * the client jar and the Fabric API module jars are remapped to Mojang's official
    names before compiling (`deobfuscate_client`, `deobfuscate_fabric_api`);
  * the compiled mod classes are remapped named -> intermediary afterwards, because
    that is the namespace Fabric Loader resolves mods against at runtime.

Everything is cached under <cache>/ so a version is only processed once. The
remapper and its libraries are fetched into <cache>/_tools.
"""
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

UA = {"User-Agent": "msa2ssid-build/1.0"}
TINY_REMAPPER = "0.15.0"
ASM = "9.9.1"
TOOL_FILES = [
    (f"https://maven.fabricmc.net/net/fabricmc/tiny-remapper/{TINY_REMAPPER}/"
     f"tiny-remapper-{TINY_REMAPPER}.jar", "tiny-remapper.jar"),
    ("https://maven.fabricmc.net/net/fabricmc/mapping-io/0.7.1/mapping-io-0.7.1.jar",
     "mapping-io.jar"),
] + [
    (f"https://repo1.maven.org/maven2/org/ow2/asm/{name}/{ASM}/{name}-{ASM}.jar",
     f"{name}.jar")
    for name in ("asm", "asm-commons", "asm-tree", "asm-util", "asm-analysis")
]


def download(url, dest):
    if os.path.exists(dest):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        data = r.read()
    tmp = dest + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dest)
    return dest


def tools(cache):
    """Downloads the remapper once and returns the directory holding it."""
    out = os.path.join(cache, "_tools")
    for url, name in TOOL_FILES:
        download(url, os.path.join(out, name))
    return out


def is_obfuscated(client_jar):
    """True when the client jar carries no readable game classes."""
    if not os.path.exists(client_jar):
        raise SystemExit(f"missing {client_jar}: run tools/fetch.py for this version first")
    with zipfile.ZipFile(client_jar) as zf:
        return "net/minecraft/client/Minecraft.class" not in zf.namelist()


def _libraries(version_dir):
    libs = os.path.join(version_dir, "libs")
    if not os.path.isdir(libs):
        return []
    return [os.path.join(libs, name) for name in sorted(os.listdir(libs))
            if name.endswith(".jar")]


def remap(cache, in_jar, out_jar, mappings, from_ns, to_ns, classpath=()):
    """Runs tiny-remapper over one jar. The classpath is only used to resolve types."""
    if os.path.exists(out_jar):
        return out_jar
    os.makedirs(os.path.dirname(out_jar) or ".", exist_ok=True)
    # tiny-remapper writes a jar only for output names ending in .jar, and class files
    # into a directory otherwise, so the half-written result keeps the extension.
    tmp = os.path.splitext(out_jar)[0] + ".part.jar"
    command = ["java", "-cp", os.path.join(tools(cache), "*"),
               "net.fabricmc.tinyremapper.Main", in_jar, tmp, mappings, from_ns, to_ns]
    command.extend(classpath)
    subprocess.run(command, check=True)
    os.replace(tmp, out_jar)
    return out_jar


def official_mappings(version_dir, meta):
    """Mojang's official mappings for one version, as a ProGuard file."""
    return download(meta["downloads"]["client_mappings"]["url"],
                    os.path.join(version_dir, "mojang-mappings.txt"))


def intermediary_mappings(version, version_dir, cache):
    """The obfuscated -> intermediary table Fabric Loader reads mods against."""
    jar = download(f"https://maven.fabricmc.net/net/fabricmc/intermediary/{version}/"
                   f"intermediary-{version}.jar",
                   os.path.join(cache, "_tools", f"intermediary-{version}.jar"))
    dest = os.path.join(version_dir, "intermediary-mappings.tiny")
    if not os.path.exists(dest):
        with zipfile.ZipFile(jar) as zf:
            with open(dest, "wb") as f:
                f.write(zf.read("mappings/mappings.tiny"))
    return dest


def deobfuscate_client(version, version_dir, meta, cache):
    """The client jar with Mojang's names, for compiling against."""
    named = os.path.join(version_dir, "client-named.jar")
    if os.path.exists(named):
        return named
    print(f"   {version} ships obfuscated: deobfuscating the client jar", file=sys.stderr)
    return remap(cache, os.path.join(version_dir, "client.jar"), named,
                 official_mappings(version_dir, meta),
                 "target", "source", _libraries(version_dir))


def _merge_jars(jars, dest):
    """One jar holding the classes of several, which is enough for a compile classpath."""
    seen = set()
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as out:
        for jar in jars:
            with zipfile.ZipFile(jar) as zf:
                for info in zf.infolist():
                    name = info.filename
                    if not name.endswith(".class") or name.startswith("META-INF/"):
                        continue
                    if name in seen:
                        continue
                    seen.add(name)
                    out.writestr(name, zf.read(name))
    return dest


def deobfuscate_fabric_api(version, version_dir, meta, cache):
    """The Fabric API classes with Mojang's names, as one jar for the classpath.

    Modules are published against intermediary. They are merged into a single jar
    first so each namespace hop runs once: every remapper run has to analyse the whole
    client jar it is given as a classpath.
    """
    named = os.path.join(version_dir, "fabric-api-named.jar")
    if os.path.exists(named):
        return named

    source = os.path.join(version_dir, "fabric-api-modules")
    jars = sorted(os.path.join(source, name) for name in os.listdir(source)
                  if name.endswith(".jar")) if os.path.isdir(source) else []
    if not jars:
        raise SystemExit(f"missing Fabric API modules for {version}")

    print(f"   {version} fabric api: remapping {len(jars)} modules to official names",
          file=sys.stderr)
    client = os.path.join(version_dir, "client.jar")
    classpath = [client] + _libraries(version_dir)
    merged = _merge_jars(jars, os.path.join(version_dir, "fabric-api-merged.jar"))
    obf = os.path.join(version_dir, "fabric-api-obf.jar")

    # intermediary -> obfuscated -> official, one pass per namespace in each table.
    remap(cache, merged, obf, intermediary_mappings(version, version_dir, cache),
          "intermediary", "official", classpath)
    remap(cache, obf, named, official_mappings(version_dir, meta),
          "target", "source", classpath)
    for path in (merged, obf):
        if os.path.exists(path):
            os.remove(path)
    return named


def remap_mod(version, cache, in_jar, out_jar):
    """Prepares the compiled mod jar for the game: copies it, or remaps to intermediary."""
    version_dir = os.path.join(cache, version)
    if not is_obfuscated(os.path.join(version_dir, "client.jar")):
        shutil.copyfile(in_jar, out_jar)
        return out_jar

    os.makedirs(os.path.dirname(out_jar) or ".", exist_ok=True)
    client = os.path.join(version_dir, "client.jar")
    named = os.path.join(version_dir, "client-named.jar")
    if not os.path.exists(named) or not os.path.exists(
            os.path.join(version_dir, "mojang-mappings.txt")):
        raise SystemExit("mappings missing: run tools/fetch.py for this version first")

    obf = os.path.join(version_dir, "mod-obf.jar")
    for path in (obf, out_jar):
        if os.path.exists(path):
            os.remove(path)
    # named -> obfuscated, then obfuscated -> intermediary.
    remap(cache, in_jar, obf, os.path.join(version_dir, "mojang-mappings.txt"),
          "source", "target", [named] + _libraries(version_dir))
    remap(cache, obf, out_jar, intermediary_mappings(version, version_dir, cache),
          "official", "intermediary", [client] + _libraries(version_dir))
    os.remove(obf)
    return out_jar


def main(argv):
    if len(argv) < 4:
        raise SystemExit(
            "usage: remapper.py is-obfuscated <version> <cache>\n"
            "       remapper.py mod <version> <cache> <in.jar> <out.jar>")
    command, version, cache = argv[1], argv[2], argv[3]

    if command == "is-obfuscated":
        print("yes" if is_obfuscated(os.path.join(cache, version, "client.jar")) else "no")
    elif command == "mod" and len(argv) >= 6:
        remap_mod(version, cache, argv[4], argv[5])
    else:
        raise SystemExit(f"unknown command: {command}")


if __name__ == "__main__":
    main(sys.argv)
