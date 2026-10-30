#!/usr/bin/env python3
"""Copy Phosphor icons (Regular weight) into the app as Android vector drawables.

Phosphor's SVGs are one filled path on a 256x256 grid, so each becomes a small
res/drawable/ph_<name>.xml that Compose draws with Icon(painterResource(...)).
To add an icon: put its name (as on phosphoricons.com) in ICONS and run

    python3 tools/phosphor.py

Phosphor Icons are MIT-licensed: licenses/MIT-Phosphor.txt.
"""
import pathlib
import re
import urllib.request

VERSION = "v2.0.8"  # a release tag of github.com/phosphor-icons/core, so the files don't drift
WEIGHT = "regular"  # or thin, light, bold, fill, duotone
ICONS = [
    "microphone", "check-square", "note-pencil",  # bottom bar
    "list", "arrows-clockwise", "x", "dots-three-vertical",
    "caret-down", "caret-right", "caret-left", "bell", "warning",
    "download-simple", "alarm",  # notifications
]

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Phosphor Icons "{name}" ({weight}), {version}, MIT. Made by tools/phosphor.py: don't edit. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="256"
    android:viewportHeight="256">
    <path
        android:fillColor="#FF000000"
        android:pathData="{path}" />
</vector>
"""


def main():
    for name in ICONS:
        # Regular files are plain "x.svg"; the other weights add the weight: "x-light.svg".
        file = name if WEIGHT == "regular" else f"{name}-{WEIGHT}"
        url = f"https://raw.githubusercontent.com/phosphor-icons/core/{VERSION}/assets/{WEIGHT}/{file}.svg"
        svg = urllib.request.urlopen(url).read().decode()
        paths = re.findall(r'<path d="([^"]+)"/>', svg)
        # The template only knows one path and no other shapes; stop rather than draw it wrong.
        if len(paths) != 1 or svg.count("<") != 3:
            raise SystemExit(f"{name}: unexpected SVG, convert it by hand: {svg}")
        out = OUT / f"ph_{name.replace('-', '_')}.xml"
        out.write_text(TEMPLATE.format(name=name, weight=WEIGHT, version=VERSION, path=paths[0]))
        print(out.relative_to(OUT.parent.parent.parent.parent.parent))


if __name__ == "__main__":
    main()
