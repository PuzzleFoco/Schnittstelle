#!/usr/bin/env python3
"""Vorsichtiger UI-Treiber für Schnittstelle auf dem Testgerät.

Prinzip: erst dumpen, dann unmittelbar vor dem Tippen prüfen, dass unsere App
wirklich im Vordergrund ist. So landet kein Tap in einer fremden App.
"""
import re
import subprocess
import sys
import time

PKG = "com.puzzlefoco.schnittstelle.debug"
ADB = "adb"


def sh(*args) -> str:
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def our_app_foreground() -> bool:
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"ResumedActivity:.*?\s(\S+)/(\S+)", out)
    return bool(m and m.group(1) == PKG)


def dump(tag="d"):
    subprocess.run([ADB, "shell", "uiautomator", "dump", f"/sdcard/{tag}.xml"],
                   capture_output=True)
    subprocess.run([ADB, "pull", f"/sdcard/{tag}.xml", f"/tmp/{tag}.xml"],
                   capture_output=True)
    return open(f"/tmp/{tag}.xml", encoding="utf-8", errors="ignore").read()


def nodes(xml):
    out = []
    for t in re.findall(r"<node[^>]*/?>", xml):
        tx = re.search(r'text="([^"]*)"', t)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            out.append(((tx.group(1) if tx else "").strip(), (x1 + x2) // 2,
                        (y1 + y2) // 2))
    return out


def texts(xml):
    return list(dict.fromkeys(re.findall(r'text="([^"]+)"', xml)))


def tap(text, tag, contains=True, index=0, label=""):
    xml = dump(tag)
    found = []
    for n in nodes(xml):
        hit = (text.lower() in n[0].lower()) if contains else (n[0] == text)
        if hit:
            found.append(n)
    if not found:
        print(f"  [{label or text}] NICHT gefunden. Sichtbar: {' | '.join(texts(xml))[:400]}")
        return False
    _, x, y = found[min(index, len(found) - 1)]
    if not our_app_foreground():
        print(f"  [{label or text}] ABBRUCH: App nicht im Vordergrund")
        return False
    subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])
    print(f"  [{label or text}] getippt bei {x},{y}")
    time.sleep(2.0)
    return True


def start():
    subprocess.run([ADB, "shell", "am", "start", "-n",
                    f"{PKG}/com.puzzlefoco.schnittstelle.ui.MainActivity"],
                   capture_output=True)
    time.sleep(3.0)
    print("Vordergrund App:", our_app_foreground())


def show(tag="s"):
    xml = dump(tag)
    print("  Paket:", set(re.findall(r'package="([^"]+)"', xml)))
    print("  Texte:", " | ".join(texts(xml))[:500])


if __name__ == "__main__":
    print("Schritt:", sys.argv[1] if len(sys.argv) > 1 else "-")
    globals()[sys.argv[1]](*sys.argv[2:]) if len(sys.argv) > 1 else show()
