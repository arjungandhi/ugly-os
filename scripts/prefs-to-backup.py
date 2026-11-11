#!/usr/bin/env python3
"""Convert a directory of Android shared_prefs XML files to the launcher's
ugly-os-backup.json format (see BackupStore.kt).

usage: prefs-to-backup.py <xml_dir> <out.json>
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

# Same list as BackupStore.PREF_FILES; home_widget is deliberately absent.
PREF_FILES = ["ugly_launcher", "app_meta", "frecency", "quick_launch", "todo_modes"]


# Android writes control chars (e.g. &#31;, the todo mode separator) as char
# refs, which XML 1.0 parsers reject. Swap them for private-use placeholders
# before parsing and back afterwards.
CHAR_REF = re.compile(r"&#(x[0-9a-fA-F]+|\d+);")
PLACEHOLDER_BASE = 0xE000


def protect(text):
    def sub(m):
        ref = m.group(1)
        n = int(ref[1:], 16) if ref[0] == "x" else int(ref)
        if n < 32 and n not in (9, 10, 13):
            return chr(PLACEHOLDER_BASE + n)
        return m.group(0)
    return CHAR_REF.sub(sub, text)


def restore(text):
    return re.sub("[\ue000-\ue01f]", lambda m: chr(ord(m.group(0)) - PLACEHOLDER_BASE), text)


def parse_prefs(path):
    entries = {}
    with open(path, encoding="utf-8") as f:
        root = ET.fromstring(protect(f.read()).encode("utf-8"))
    for el in root:
        name = restore(el.get("name"))
        tag = el.tag
        if tag == "string":
            entries[name] = {"type": "string", "value": restore(el.text or "")}
        elif tag in ("int", "long"):
            entries[name] = {"type": tag, "value": int(el.get("value"))}
        elif tag == "float":
            entries[name] = {"type": "float", "value": float(el.get("value"))}
        elif tag == "boolean":
            entries[name] = {"type": "boolean", "value": el.get("value") == "true"}
        elif tag == "set":
            entries[name] = {"type": "stringset", "value": [restore(s.text or "") for s in el]}
        else:
            print(f"warning: skipping {name!r} of unknown type {tag}", file=sys.stderr)
    return entries


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    xml_dir, out = sys.argv[1:]
    prefs = {}
    for name in PREF_FILES:
        path = os.path.join(xml_dir, name + ".xml")
        if os.path.isfile(path):
            prefs[name] = parse_prefs(path)
        else:
            print(f"warning: {path} missing, skipped", file=sys.stderr)
    with open(out, "w") as f:
        json.dump({"version": 1, "prefs": prefs}, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
