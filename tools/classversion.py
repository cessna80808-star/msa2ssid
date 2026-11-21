#!/usr/bin/env python3
"""Fails when a mod jar carries classes newer than the Java runtime it targets.

usage: classversion.py <jar> <java-version>

A jar declaring "java": ">=21" must not contain class file version 69 (Java 25):
the game would get as far as loading it and then die with an
UnsupportedClassVersionError.
"""
import sys
import zipfile

# Java N is class file major version N + 44; Java 21 -> 65, Java 25 -> 69.
MAJOR_OFFSET = 44


def main():
    jar, java = sys.argv[1], int(sys.argv[2])
    limit = java + MAJOR_OFFSET
    worst = (0, None)
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if not name.endswith(".class"):
                continue
            head = zf.read(name)[:8]
            major = int.from_bytes(head[6:8], "big")
            if major > worst[0]:
                worst = (major, name)
    if worst[0] > limit:
        print(f"error: {jar} needs Java {worst[0] - MAJOR_OFFSET} ({worst[1]}) "
              f"but targets Java {java}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
