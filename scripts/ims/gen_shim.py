import collections
import os
import struct
import sys
import zipfile

HAND_WRITTEN = {
    "Landroid/os/SemSystemProperties;",
    "Lcom/samsung/android/feature/SemFloatingFeature;",
    "Lcom/samsung/android/feature/SemCscFeature;",
    "Lcom/samsung/android/feature/SemCarrierFeature;",
    "Lcom/android/internal/telephony/ISemTelephony;",
    "Lcom/android/internal/telephony/ISemTelephony$Stub;",
    "Landroid/sec/enterprise/PhoneRestrictionPolicy;",
    "Lcom/android/internal/telephony/TelephonyFeatures;",
    "Lcom/samsung/android/emergencymode/SemEmergencyConstants;",
}
SUPPLIED_BY_JARS = ("Lcom/gsma/", "Lcom/sec/ims/", "Lcom/sec/vsim/", "Lcom/google/", "Lorg/", "Lcom/stanfy/", "Lcom/sec/epdg/", "Lvendor/samsung/hardware/radio/channel/ISehRadioChannel")

src = open(sys.argv[1]).read().split("TM = ")[0]
exec(src)


def uleb(d, p):
    r = s = 0
    while True:
        b = d[p]
        p += 1
        r |= (b & 127) << s
        if not b & 128:
            return r, p
        s += 7


exec(open(sys.argv[1]).read().split("WIDTHS = {}")[1].join(["WIDTHS = {}", ""]).split("def write_units")[0])

types, apk, out = sys.argv[2], sys.argv[3], sys.argv[4]
missing = {t for t in open(types).read().split() if not t.startswith(SUPPLIED_BY_JARS)}
raw = zipfile.ZipFile(apk).read("classes.dex")
dex = Dex(raw)
kinds = collections.defaultdict(set)
methods = collections.defaultdict(lambda: collections.defaultdict(set))
fields = collections.defaultdict(set)
static_fields = collections.defaultdict(set)
for i in range(dex.n_method):
    c, n, p = dex.method(i)
    if c in missing:
        methods[c][(n, p)] = set()
for i in range(dex.n_field):
    c, t, n = struct.unpack_from("<HHI", raw, dex.o_field + i * 8)
    owner = dex.t(c)
    if owner in missing:
        fields[owner].add((dex.s(n), dex.t(t)))
for code in dex.code_items():
    insns = struct.unpack_from("<I", raw, code + 12)[0]
    base = code + 16
    p = 0
    while p < insns:
        op = raw[base + p * 2]
        hi = raw[base + p * 2 + 1]
        if op == 0 and hi:
            if hi == 1:
                p += struct.unpack_from("<H", raw, base + p * 2 + 2)[0] * 2 + 4
            elif hi == 2:
                p += struct.unpack_from("<H", raw, base + p * 2 + 2)[0] * 4 + 2
            elif hi == 3:
                w = struct.unpack_from("<H", raw, base + p * 2 + 2)[0]
                size = struct.unpack_from("<I", raw, base + p * 2 + 4)[0]
                p += (size * w + 1) // 2 + 4
            else:
                p += 1
            continue
        if 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78:
            c, n, pr = dex.method(struct.unpack_from("<H", raw, base + p * 2 + 2)[0])
            if c in missing:
                kind = {0x6e: "virtual", 0x6f: "super", 0x70: "direct", 0x71: "static", 0x72: "interface", 0x74: "virtual", 0x75: "super", 0x76: "direct", 0x77: "static", 0x78: "interface"}[op]
                methods[c][(n, pr)].add(kind)
                kinds[c].add(kind)
        p += WIDTHS.get(op, 1)
parents = set()
for i in range(dex.n_class):
    cls, _, sup = struct.unpack_from("<III", raw, dex.o_class + i * 32)
    if sup != 0xFFFFFFFF and dex.t(sup) in missing:
        parents.add(dex.t(sup))


def java_type(t):
    dims = 0
    while t.startswith("["):
        dims += 1
        t = t[1:]
    base = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char", "I": "int", "J": "long", "F": "float", "D": "double"}.get(t)
    if base is None:
        base = t[1:-1].replace("/", ".").replace("$", ".")
    return base + "[]" * dims


def parse_proto(proto):
    args = []
    p = 1
    while proto[p] != ")":
        start = p
        while proto[p] == "[":
            p += 1
        if proto[p] == "L":
            p = proto.index(";", p)
        p += 1
        args.append(proto[start:p])
    return args, proto[p + 1:]


def default_value(ret, owner):
    if ret == "V":
        return None
    if ret == "Z":
        return "false"
    if ret in "BSCIJFD":
        return "0"
    if ret == owner:
        return None
    if ret in missing and "interface" not in kinds.get(ret, set()) and not ret.endswith("$Stub;"):
        return "new " + java_type(ret) + "()"
    return "null"


tree = collections.defaultdict(list)
for cls in sorted(missing):
    if cls in HAND_WRITTEN:
        continue
    inner = cls[1:-1]
    tree[inner.split("$")[0]].append(cls)


def emit_class(cls, nested):
    simple = cls[1:-1].split("/")[-1].split("$")[-1]
    is_interface = "interface" in kinds.get(cls, set()) and cls not in parents
    lines = []
    if simple == "Stub":
        outer = "L" + cls[1:-1].rsplit("$", 1)[0] + ";"
        return ["public abstract static class Stub extends android.os.Binder implements " + java_type(outer) + " {",
                "    public Stub() {", "    }", "",
                "    @Override", "    public android.os.IBinder asBinder() {", "        return this;", "    }", "}"]
    head = "public " + ("static " if nested else "") + ("interface " if is_interface else "class ") + simple
    if is_interface and any(c.endswith("$Stub;") and c.startswith(cls[:-1]) for c in missing):
        head += " extends android.os.IInterface"
    lines.append(head + " {")
    for name, typ in sorted(fields.get(cls, [])):
        lines.append("    public " + ("static final " if is_interface else "") + java_type(typ) + " " + name + (" = " + ("false" if typ == "Z" else "0" if typ in "BSCIJFD" else "null") if is_interface else "") + ";")
    ctors = [p for (n, p) in methods.get(cls, {}) if n == "<init>"]
    if not is_interface:
        lines.append("    public " + simple + "() {")
        lines.append("    }")
    for proto in sorted(ctors):
        args, _ = parse_proto(proto)
        if not args:
            continue
        params = ", ".join(java_type(a) + " a" + str(i) for i, a in enumerate(args))
        lines.append("    public " + simple + "(" + params + ") {")
        lines.append("    }")
    for (name, proto), used in sorted(methods.get(cls, {}).items()):
        if name == "<init>" or name == "asBinder" and is_interface and any(c.endswith("$Stub;") and c.startswith(cls[:-1]) for c in missing):
            continue
        args, ret = parse_proto(proto)
        params = ", ".join(java_type(a) + " a" + str(i) for i, a in enumerate(args))
        static = "static" in used
        if is_interface:
            lines.append("    " + java_type(ret) + " " + name + "(" + params + ");")
            continue
        lines.append("    public " + ("static " if static else "") + java_type(ret) + " " + name + "(" + params + ") {")
        value = default_value(ret, cls)
        if ret == cls:
            lines.append("        return " + ("new " + simple + "()" if static else "this") + ";")
        elif value is not None:
            lines.append("        return " + value + ";")
        lines.append("    }")
    return lines


written = 0
for outer, members in sorted(tree.items()):
    outer_type = "L" + outer + ";"
    package = "/".join(outer.split("/")[:-1]).replace("/", ".")
    path = os.path.join(out, outer + ".java")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = emit_class(outer_type, False) if outer_type in members else ["public class " + outer.split("/")[-1] + " {"]
    if outer_type not in members and all(m.endswith("$Stub;") for m in members):
        body = ["public interface " + outer.split("/")[-1] + " extends android.os.IInterface {"]
    for member in members:
        if member == outer_type:
            continue
        nested = emit_class(member, True)
        if not member.endswith("$Stub;"):
            nested.append("}")
        body += ["    " + line for line in nested]
    body.append("}")
    with open(path, "w") as handle:
        handle.write("package " + package + ";\n\n" + "\n".join(body) + "\n")
    written += 1
print("generated", written, "files for", sum(len(m) for m in tree.values()), "classes")
