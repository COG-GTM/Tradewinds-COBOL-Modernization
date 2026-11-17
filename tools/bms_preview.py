#!/usr/bin/env python3
"""Render CICS BMS maps (src/base/bms_src/*.bms) as 3270-style screens without a CICS region.

Usage:
    python3 tools/bms_preview.py                 # writes bms_preview.html next to the repo root
    python3 tools/bms_preview.py --text          # prints every screen as plain 24x80 text
    python3 tools/bms_preview.py -o out.html BNK1MAI.bms
"""
import argparse
import glob
import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BMS_DIR = os.path.join(ROOT, "src", "base", "bms_src")
COLORS = {"BLUE": "#5c8aff", "RED": "#ff5c5c", "PINK": "#ff7ad9", "GREEN": "#4cff7a", "TURQUOISE": "#4ce0ff",
          "YELLOW": "#ffff5c", "NEUTRAL": "#f0f0f0", "DEFAULT": "#4cff7a"}


def statements(path):
    """Yield (label, macro, operand_text) with continuation lines joined."""
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = [l.rstrip("\r\n") for l in f]
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip() or line.startswith("*"):
            i += 1
            continue
        text = line[:71]
        while len(line) > 71 and line[71] != " " and i + 1 < len(lines):
            i += 1
            line = lines[i]
            text += line[15:71]
        i += 1
        label = text[:8].strip()
        rest = text[8:].lstrip()
        macro, _, ops = rest.partition(" ")
        yield label, macro, ops


def operands(ops):
    """Split 'POS=(1,2),INITIAL='a,b'' into a dict, honouring quotes and parentheses."""
    out, key, buf, depth, quoted, i = {}, None, "", 0, False, 0
    while i < len(ops):
        ch = ops[i]
        if quoted:
            if ch == "'" and i + 1 < len(ops) and ops[i + 1] == "'":
                buf += "'"
                i += 1
            elif ch == "'":
                quoted = False
            else:
                buf += ch
        elif ch == "'":
            quoted = True
        elif ch == " ":
            pass
        elif ch == "(":
            depth += 1
            buf += ch
        elif ch == ")":
            depth -= 1
            buf += ch
        elif ch == "=" and depth == 0 and key is None:
            key, buf = buf.upper(), ""
        elif ch == "," and depth == 0:
            if key:
                out[key] = buf
            key, buf = None, ""
        else:
            buf += ch
        i += 1
    if key:
        out[key] = buf
    return out


def parse_map(path):
    maps, current = [], None
    for label, macro, ops in statements(path):
        if macro == "DFHMDI":
            current = {"name": label, "fields": []}
            maps.append(current)
        elif macro == "DFHMDF" and current is not None:
            o = operands(ops)
            pos = re.match(r"\((\d+),(\d+)\)", o.get("POS", ""))
            if not pos:
                continue
            attrb = set(o.get("ATTRB", "").strip("()").upper().split(","))
            protected = bool(attrb & {"PROT", "ASKIP"})
            # BMS assumes UNPROT for NUM or IC fields that are not PROT/ASKIP.
            is_input = "UNPROT" in attrb or (not protected and bool(attrb & {"NUM", "IC"}))
            current["fields"].append({
                "label": label, "row": int(pos.group(1)), "col": int(pos.group(2)),
                "length": int(o.get("LENGTH", "0") or 0), "initial": o.get("INITIAL"),
                "color": o.get("COLOR", "DEFAULT"), "input": is_input,
                "dark": "DRK" in attrb})
    return maps


def render(m):
    grid = [[(" ", "DEFAULT", False) for _ in range(80)] for _ in range(24)]
    for f in m["fields"]:
        if f["dark"]:
            continue
        text = f["initial"] if f["initial"] is not None else ("_" * f["length"] if f["input"] else "")
        text = text[:f["length"]].ljust(f["length"]) if f["input"] else text[:f["length"]]
        r, c = f["row"] - 1, f["col"]
        for k, ch in enumerate(text):
            if 0 <= r < 24 and c + k < 80:
                grid[r][c + k] = (ch, f["color"], f["input"])
    return grid


def to_text(grid):
    return "\n".join("".join(ch for ch, _, _ in row).rstrip() for row in grid)


def to_html(screens):
    parts = ["<!doctype html><html><head><meta charset='utf-8'><title>Pharma Trade Desk - 3270 screens</title>",
             "<style>body{background:#111;color:#ddd;font-family:sans-serif;margin:24px}",
             "h2{font-size:15px;margin:28px 0 8px}pre{background:#000;padding:12px 16px;display:inline-block;",
             "font:15px/1.25 'IBM Plex Mono',Consolas,monospace;border:1px solid #333;border-radius:4px}",
             ".in{text-decoration:underline}</style></head><body>",
             "<h1>Menarini Pharma Trade Desk: legacy 3270 (BMS) screens</h1>",
             "<p>Rendered from <code>src/base/bms_src</code> by <code>tools/bms_preview.py</code>; no CICS region needed.</p>"]
    for title, grid in screens:
        parts.append("<h2 id='%s'>%s</h2><pre>" % (html.escape(title), html.escape(title)))
        for row in grid:
            for ch, color, is_input in row:
                cls = " class='in'" if is_input else ""
                parts.append("<span style='color:%s'%s>%s</span>" % (COLORS.get(color, COLORS["DEFAULT"]), cls,
                                                                     html.escape(ch)))
            parts.append("\n")
        parts.append("</pre>")
    parts.append("</body></html>")
    return "".join(parts)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help="BMS files (default: all in src/base/bms_src)")
    ap.add_argument("--text", action="store_true", help="print plain text instead of writing HTML")
    ap.add_argument("-o", "--output", default=os.path.join(ROOT, "bms_preview.html"))
    a = ap.parse_args(argv)
    files = [f if os.path.exists(f) else os.path.join(BMS_DIR, f) for f in a.files] or \
        sorted(glob.glob(os.path.join(BMS_DIR, "*.bms")))
    screens = []
    for path in files:
        for m in parse_map(path):
            screens.append(("%s / %s" % (os.path.basename(path), m["name"]), render(m)))
    if a.text:
        for title, grid in screens:
            print("=" * 80 + "\n" + title + "\n" + "=" * 80)
            print(to_text(grid))
        return 0
    with open(a.output, "w", encoding="utf-8") as f:
        f.write(to_html(screens))
    print("wrote %s (%d screens)" % (a.output, len(screens)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
