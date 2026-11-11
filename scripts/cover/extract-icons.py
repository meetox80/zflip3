import re
import subprocess
import sys
from pathlib import Path

AAPT2 = sys.argv[1]
SYSTEMUI = sys.argv[2]
AOD = sys.argv[3]
OUT = Path(sys.argv[4])

SYSTEMUI_ICONS = {
    "ic_tile_bluetooth": "quick_panel_icon_bluetooth",
    "ic_tile_sound": "subroom_quick_panel_icon_sound",
    "ic_tile_brightness": "subroom_quick_panel_icon_brightness",
    "ic_back": "subroom_back_button",
    "ic_detail_back": "subscreen_notification_detail_back_button_icon",
    "ic_volume_media": "ic_volume_media",
    "ic_volume_media_mute": "ic_volume_media_mute",
    "ic_volume_ringer": "ic_volume_ringer",
    "ic_volume_ringer_mute": "ic_volume_ringer_mute",
    "ic_volume_ringer_vibrate": "ic_volume_ringer_vibrate",
    "ic_volume_call": "tw_ic_audio_call_mtrl",
}
AOD_ICONS = {
    "ic_music_play": "ic_sub_ui_music_play",
    "ic_no_item_alarm": "ic_no_item_alarm",
    "ic_no_item_schedule": "ic_no_item_schedule",
    "ic_add_button": "ic_add_button",
    "ic_minus": "ic_minus",
    "ic_security": "sub_ui_ic_security",
    "ic_music_pause": "ic_sub_ui_music_pause",
    "ic_music_next": "ic_sub_ui_music_next",
    "ic_music_previous": "ic_sub_ui_music_previous",
    "ic_music_album": "ic_stat_notify_music_album",
    "ic_music_note": "ic_music_app_name",
}

ANDROID = "http://schemas.android.com/apk/res/android:"
FLOAT_ATTRS = {
    "viewportWidth", "viewportHeight", "strokeWidth", "strokeAlpha", "fillAlpha", "alpha",
    "translateX", "translateY", "scaleX", "scaleY", "pivotX", "pivotY", "rotation",
    "trimPathStart", "trimPathEnd", "trimPathOffset", "strokeMiterLimit",
}
ENUMS = {
    "fillType": {0: "nonZero", 1: "evenOdd"},
    "strokeLineCap": {0: "butt", 1: "round", 2: "square"},
    "strokeLineJoin": {0: "miter", 1: "round", 2: "bevel"},
}
KEEP = FLOAT_ATTRS | set(ENUMS) | {"width", "height", "pathData", "fillColor", "strokeColor", "name", "tint", "autoMirrored"}
ATTRIBUTE = re.compile(r"A: (\S+?)(?:\(0x[0-9a-f]+\))?=(.*)$")


def dump(*args):
    return subprocess.run([AAPT2, "dump", *args], capture_output=True, text=True, check=True).stdout


def resource_table(apk):
    files = {}
    values = {}
    current = None
    for line in dump("resources", apk).splitlines():
        resource = re.match(r"\s+resource (0x[0-9a-f]+) (\w+)/(\S+)$", line)
        if resource:
            current = resource.groups()
            continue
        entry = re.match(r"\s+\(\) (.*)$", line)
        if not entry or not current:
            continue
        resource_id, kind, name = current
        file_entry = re.match(r"\(file\) (\S+) type=XML$", entry.group(1))
        if kind == "drawable" and file_entry:
            files.setdefault(name, file_entry.group(1))
        elif not file_entry:
            values.setdefault("@" + resource_id, entry.group(1))
    return files, values


def convert_value(name, value, values):
    value = values.get(value, value)
    if value.startswith('"'):
        value = value[1:value.index('"', 1)]
    if name in FLOAT_ATTRS:
        return "%g" % float(value)
    if name in ENUMS:
        return ENUMS[name][int(value, 0)]
    if name in ("width", "height"):
        match = re.search(r"([\d.]+)(?:px|dp)", value)
        return "%gdp" % float(match.group(1))
    if name in ("fillColor", "strokeColor", "tint"):
        match = re.search(r"#[0-9a-f]{8}", value)
        return match.group(0) if match else None
    return value


def parse(apk, path):
    elements = []
    indents = []
    for line in dump("xmltree", "--file", path, apk).splitlines():
        indent = len(line) - len(line.lstrip(" "))
        body = line.strip()
        if body.startswith("E: "):
            while indents and indents[-1] >= indent:
                indents.pop()
            elements.append((len(indents), body[3:].split(" ", 1)[0], []))
            indents.append(indent)
        elif body.startswith("A: ") and elements:
            match = ATTRIBUTE.match(body)
            if match and match.group(1).startswith(ANDROID):
                elements[-1][2].append((match.group(1)[len(ANDROID):], match.group(2)))
    return elements


def to_xml(elements, values):
    out = ['<?xml version="1.0" encoding="utf-8"?>']
    stack = []
    for index, (depth, tag, attrs) in enumerate(elements):
        while len(stack) > depth:
            closing = stack.pop()
            out.append("  " * len(stack) + "</%s>" % closing)
        rendered = ['xmlns:android="http://schemas.android.com/apk/res/android"'] if index == 0 else []
        for name, value in attrs:
            if name not in KEEP:
                continue
            converted = convert_value(name, value, values)
            if converted is not None:
                rendered.append('android:%s="%s"' % (name, converted))
        out.append("  " * depth + "<%s %s>" % (tag, " ".join(rendered)))
        stack.append(tag)
    while stack:
        closing = stack.pop()
        out.append("  " * len(stack) + "</%s>" % closing)
    return "\n".join(out) + "\n"


def extract(apk, icons):
    files, values = resource_table(apk)
    failed = 0
    for target, source in icons.items():
        path = files.get(source)
        elements = parse(apk, path) if path else []
        if not elements or elements[0][1] != "vector":
            print("missing vector", source, "in", Path(apk).name, file=sys.stderr)
            failed += 1
            continue
        (OUT / (target + ".xml")).write_text(to_xml(elements, values))
    return failed


OUT.mkdir(parents=True, exist_ok=True)
if extract(SYSTEMUI, SYSTEMUI_ICONS) + extract(AOD, AOD_ICONS):
    sys.exit(1)
