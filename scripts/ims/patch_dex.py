import hashlib
import struct
import sys
import zlib

V = 0
NOP = ("nop",)


def const(value):
    return ("const", value)


def wide(value):
    return ("wide", value)


def retarget(cls, name, proto, drop=0):
    return ("retarget", (cls, name, proto), drop)


def result_arg(index):
    return ("result_arg", index)


TM = "Landroid/telephony/TelephonyManager;"
SS = "Landroid/telephony/ServiceState;"
STR = "Ljava/lang/String;"
NM = "Landroid/os/INetworkManagementService;"
LISTENER = "Landroid/telephony/ims/aidl/IImsCallSessionListener;"
SMS = "Landroid/telephony/ims/stub/ImsSmsImplBase;"
METHODS = {
    (TM, "semGetServiceState", "(I)" + SS): retarget(TM, "getServiceStateForSubscriber", "(I)" + SS),
    (TM, "getSubscriberIdForUiccAppType", "(II)" + STR): retarget(TM, "getSubscriberId", "(I)" + STR, 1),
    (TM, "getAllCellInfoBySubId", "(I)Ljava/util/List;"): retarget(TM, "getAllCellInfo", "()Ljava/util/List;", 1),
    (TM, "semGetTelephonyProperty", "(I" + STR + STR + ")" + STR): result_arg(-1),
    (TM, "semGetDataServiceState", "(I)I"): const(0),
    (TM, "semGetDataState", "(I)I"): const(2),
    (TM, "semGetNrMode", "()I"): const(0),
    (TM, "semIsVoNrEnabled", "()Z"): const(0),
    (TM, "semSetNrMode", "(IIZ)Z"): const(0),
    (TM, "getCurrentPhoneTypeForSlot", "(I)I"): const(1),
    (TM, "getGroupIdLevel2", "(I)" + STR): const(0),
    (TM, "checkCallControl", "(" + STR + ")" + STR): result_arg(-1),
    (TM, "hasCall", "(" + STR + ")Z"): const(0),
    (TM, "getBtid", "()" + STR): const(0),
    (TM, "getKeyLifetime", "()" + STR): const(0),
    (TM, "getRand", "()[B"): const(0),
    (TM, "getNextRetryTime", "()J"): wide(0),
    (TM, "isGbaSupported", "()Z"): const(0),
    (TM, "isGbaSupported", "(I)Z"): const(0),
    (TM, "setGbaBootstrappingParams", "([B" + STR + STR + ")V"): NOP,
    (TM, "invokeOemRilRequestRawForPhone", "(I[B[B)I"): const(-1),
    (SS, "getMobileDataRegState", "()I"): retarget(SS, "getDataRegState", "()I"),
    (SS, "getSnapshotStatus", "()I"): const(0),
    (SS, "isPsOnlyReg", "()Z"): const(0),
    ("Landroid/media/ToneGenerator;", "semSetVolume", "(F)V"): NOP,
    (NM, "startNetworkStatsOnPorts", "(" + STR + "II)V"): NOP,
    (NM, "stopNetworkStatsOnPorts", "(" + STR + "II)V"): NOP,
    (NM, "getNetworkStatsVideoCall", "(" + STR + "II)J"): wide(0),
    (LISTENER, "callSessionCancelTransferFailed", "(Landroid/telephony/ims/ImsReasonInfo;)V"): NOP,
    (LISTENER, "callSessionCancelTransferred", "()V"): NOP,
    (LISTENER, "callSessionImsCallEvent", "(" + STR + "Landroid/os/Bundle;)V"): NOP,
    (SMS, "onReceiveSmsDeliveryReportAck", "(II)V"): NOP,
    (SMS, "onSendSmsResultIncludeErrClass", "(IIIIII)V"): retarget(SMS, "onSendSmsResultError", "(IIIII)V", 1),
    ("Lcom/android/internal/telephony/uicc/IccUtils;", "getIccType", "(I)I"): const(0),
    ("Landroid/telephony/ims/ImsCallProfile;", "setCallExtraStringArray", "(" + STR + "[" + STR + ")V"): NOP,
    ("Landroid/telephony/ims/ImsSsInfo$Builder;", "setServiceClass", "(I)Landroid/telephony/ims/ImsSsInfo$Builder;"): result_arg(0),
    ("Landroid/os/ServiceManager;", "addService", "(" + STR + "Landroid/os/IBinder;)V"): NOP,
    ("Landroid/os/SystemProperties;", "set", "(" + STR + STR + ")V"): retarget("Landroid/os/SemSystemProperties;", "set", "(" + STR + STR + ")V"),
}
DROP_INTERFACES = {
    "Lcom/sec/internal/ims/core/TelephonyCallbackForPdnController;": "Landroid/telephony/TelephonyCallback$CellInfoListener;",
}
REPLACE_STRINGS = {
    ("Lcom/sec/internal/ims/core/handler/secims/PaniGenerator$PaniGeneratorBase;", "generateLtePani"): ("", "3GPP-E-UTRAN-FDD"),
}
SINGLE_USER = ("Lcom/sec/ims/extensions/ContextExt;", "OWNER")
OTHER_USERS = {
    ("Landroid/os/UserHandle;", "ALL"),
    ("Landroid/os/UserHandle;", "CURRENT"),
    ("Landroid/os/UserHandle;", "SEM_CURRENT"),
    ("Lcom/sec/ims/extensions/ContextExt;", "ALL"),
    ("Lcom/sec/ims/extensions/ContextExt;", "CURRENT"),
    ("Lcom/sec/ims/extensions/ContextExt;", "CURRENT_OR_SELF"),
}
SEM_INT_VALUE = int(sys.argv[3]) if len(sys.argv) > 3 else 0


def uleb(d, p):
    r = s = 0
    while True:
        b = d[p]
        p += 1
        r |= (b & 127) << s
        if not b & 128:
            return r, p
        s += 7


WIDTHS = {}
for size, ranges in {
    1: [(0x00, 0x01), (0x04, 0x04), (0x07, 0x07), (0x0a, 0x12), (0x1d, 0x1e), (0x21, 0x21), (0x27, 0x28), (0x3e, 0x43), (0x73, 0x73), (0x79, 0x7a), (0x7b, 0x8f), (0xb0, 0xcf), (0xe3, 0xf9)],
    2: [(0x02, 0x02), (0x05, 0x05), (0x08, 0x08), (0x13, 0x13), (0x15, 0x16), (0x19, 0x1a), (0x1c, 0x1c), (0x1f, 0x20), (0x22, 0x23), (0x29, 0x29), (0x2d, 0x3d), (0x44, 0x6d), (0x90, 0xaf), (0xd0, 0xe2), (0xfe, 0xff)],
    3: [(0x03, 0x03), (0x06, 0x06), (0x09, 0x09), (0x14, 0x14), (0x17, 0x17), (0x1b, 0x1b), (0x24, 0x26), (0x2a, 0x2c), (0x6e, 0x72), (0x74, 0x78), (0xfc, 0xfd)],
    4: [(0xfa, 0xfb)],
    5: [(0x18, 0x18)],
}.items():
    for lo, hi in ranges:
        for op in range(lo, hi + 1):
            WIDTHS[op] = size


class Dex:
    def __init__(self, data):
        self.d = data
        h = struct.unpack_from("<20I", data, 0x38)
        self.o_str, self.o_type = h[1], h[3]
        self.o_proto, self.n_field, self.o_field, self.n_method, self.o_method = h[5], h[6], h[7], h[8], h[9]
        self.n_class, self.o_class = h[10], h[11]
        self.sc = {}
        self.pc = {}

    def s(self, i):
        v = self.sc.get(i)
        if v is None:
            off = struct.unpack_from("<I", self.d, self.o_str + i * 4)[0]
            _, p = uleb(self.d, off)
            v = bytes(self.d[p:self.d.index(b"\0", p)]).decode("utf-8", "replace")
            self.sc[i] = v
        return v

    def t(self, i):
        return self.s(struct.unpack_from("<I", self.d, self.o_type + i * 4)[0])

    def proto(self, i):
        v = self.pc.get(i)
        if v is None:
            _, ret, params = struct.unpack_from("<III", self.d, self.o_proto + i * 12)
            args = ""
            if params:
                c = struct.unpack_from("<I", self.d, params)[0]
                args = "".join(self.t(struct.unpack_from("<H", self.d, params + 4 + k * 2)[0]) for k in range(c))
            v = "(" + args + ")" + self.t(ret)
            self.pc[i] = v
        return v

    def method(self, i):
        c, pr, n = struct.unpack_from("<HHI", self.d, self.o_method + i * 8)
        return self.t(c), self.s(n), self.proto(pr)

    def field(self, i):
        c, _, n = struct.unpack_from("<HHI", self.d, self.o_field + i * 8)
        return self.t(c), self.s(n)

    def code_items(self):
        d = self.d
        for i in range(self.n_class):
            data = struct.unpack_from("<I", d, self.o_class + i * 32 + 24)[0]
            if not data:
                continue
            p = data
            sf, p = uleb(d, p)
            inf, p = uleb(d, p)
            dm, p = uleb(d, p)
            vm, p = uleb(d, p)
            for _ in range(sf + inf):
                _, p = uleb(d, p)
                _, p = uleb(d, p)
            for _ in range(dm + vm):
                _, p = uleb(d, p)
                _, p = uleb(d, p)
                code, p = uleb(d, p)
                if code:
                    yield code


def write_units(d, at, units):
    for index, unit in enumerate(units):
        struct.pack_into("<H", d, at + index * 2, unit & 0xFFFF)


def drop_interfaces(d, dex):
    dropped = 0
    for i in range(dex.n_class):
        at = dex.o_class + i * 32
        target = DROP_INTERFACES.get(dex.t(struct.unpack_from("<I", d, at)[0]))
        if target is None:
            continue
        offset = struct.unpack_from("<I", d, at + 12)[0]
        size = struct.unpack_from("<I", d, offset)[0]
        if size % 2:
            raise SystemExit("cannot shrink an odd interface list in place")
        entries = [struct.unpack_from("<H", d, offset + 4 + k * 2)[0] for k in range(size)]
        kept = [entry for entry in entries if dex.t(entry) != target]
        if len(kept) != size - 1:
            raise SystemExit("interface not found: " + target)
        struct.pack_into("<I", d, offset, len(kept))
        for k in range(size):
            struct.pack_into("<H", d, offset + 4 + k * 2, kept[k] if k < len(kept) else 0)
        dropped += 1
    if dropped != len(DROP_INTERFACES):
        raise SystemExit("class for interface removal not found")
    return dropped


def replace_strings(d, dex):
    wanted = {text for pair in REPLACE_STRINGS.values() for text in pair}
    index = {}
    for i in range(struct.unpack_from("<I", d, 0x38)[0]):
        value = dex.s(i)
        if value in wanted:
            index[value] = i
    missing = wanted - set(index)
    if missing:
        raise SystemExit("missing strings " + str(missing))
    replaced = 0
    for i in range(dex.n_class):
        at = dex.o_class + i * 32
        owner = dex.t(struct.unpack_from("<I", d, at)[0])
        data = struct.unpack_from("<I", d, at + 24)[0]
        if not data or not any(key[0] == owner for key in REPLACE_STRINGS):
            continue
        p = data
        static_fields, p = uleb(d, p)
        instance_fields, p = uleb(d, p)
        direct_methods, p = uleb(d, p)
        virtual_methods, p = uleb(d, p)
        for _ in range(static_fields + instance_fields):
            _, p = uleb(d, p)
            _, p = uleb(d, p)
        for count in (direct_methods, virtual_methods):
            method_id = 0
            for _ in range(count):
                step, p = uleb(d, p)
                method_id += step
                _, p = uleb(d, p)
                code, p = uleb(d, p)
                pair = REPLACE_STRINGS.get((owner, dex.method(method_id)[1]))
                if not pair or not code:
                    continue
                size = struct.unpack_from("<I", d, code + 12)[0]
                base = code + 16
                unit = 0
                while unit < size:
                    op = d[base + unit * 2]
                    if op == 0x1a and struct.unpack_from("<H", d, base + unit * 2 + 2)[0] == index[pair[0]]:
                        struct.pack_into("<H", d, base + unit * 2 + 2, index[pair[1]])
                        replaced += 1
                    unit += WIDTHS.get(op, 1)
    if replaced != len(REPLACE_STRINGS):
        raise SystemExit("expected one string replacement per entry, got " + str(replaced))


def patch(data):
    d = bytearray(data)
    dex = Dex(d)
    drop_interfaces(d, dex)
    replace_strings(d, dex)
    method_index = {}
    target_ids = {}
    for i in range(dex.n_method):
        key = dex.method(i)
        method_index[key] = i
        if key in METHODS:
            target_ids[i] = METHODS[key]
    for key, action in METHODS.items():
        if action[0] == "retarget" and action[1] not in method_index:
            raise SystemExit("missing retarget " + str(action[1]))
    sem_int = single_user = None
    other_users = set()
    for i in range(dex.n_field):
        key = dex.field(i)
        if key == ("Landroid/os/Build$VERSION;", "SEM_INT"):
            sem_int = i
        elif key == SINGLE_USER:
            single_user = i
        elif key in OTHER_USERS:
            other_users.add(i)
    if single_user is None:
        raise SystemExit("missing field " + str(SINGLE_USER))
    counts = {}
    for code in dex.code_items():
        insns = struct.unpack_from("<I", d, code + 12)[0]
        base = code + 16
        p = 0
        while p < insns:
            op = d[base + p * 2]
            hi = d[base + p * 2 + 1]
            if op == 0 and hi:
                if hi == 1:
                    p += struct.unpack_from("<H", d, base + p * 2 + 2)[0] * 2 + 4
                elif hi == 2:
                    p += struct.unpack_from("<H", d, base + p * 2 + 2)[0] * 4 + 2
                elif hi == 3:
                    w = struct.unpack_from("<H", d, base + p * 2 + 2)[0]
                    size = struct.unpack_from("<I", d, base + p * 2 + 4)[0]
                    p += (size * w + 1) // 2 + 4
                else:
                    p += 1
                continue
            width = WIDTHS.get(op, 1)
            at = base + p * 2
            if op == 0x60 or op == 0x62:
                f = struct.unpack_from("<H", d, at + 2)[0]
                if f == sem_int:
                    write_units(d, at, [0x13 | (hi << 8), SEM_INT_VALUE])
                    counts["SEM_INT"] = counts.get("SEM_INT", 0) + 1
                elif f in other_users:
                    struct.pack_into("<H", d, at + 2, single_user)
                    counts["user handle"] = counts.get("user handle", 0) + 1
            elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78:
                mid = struct.unpack_from("<H", d, at + 2)[0]
                action = target_ids.get(mid)
                if action:
                    apply_action(d, dex, at, op, hi, base, p, insns, action, method_index)
                    name = dex.method(mid)[1]
                    counts[name] = counts.get(name, 0) + 1
            p += width
    d[12:32] = hashlib.sha1(bytes(d[32:])).digest()
    struct.pack_into("<I", d, 8, zlib.adler32(bytes(d[12:])) & 0xFFFFFFFF)
    return bytes(d), counts


def registers(d, at, op, hi):
    if op >= 0x74:
        first = struct.unpack_from("<H", d, at + 4)[0]
        return [first + i for i in range(hi)]
    count = hi >> 4
    word = struct.unpack_from("<H", d, at + 4)[0]
    regs = [word & 15, (word >> 4) & 15, (word >> 8) & 15, (word >> 12) & 15, hi & 15]
    return regs[:count]


def apply_action(d, dex, at, op, hi, base, p, insns, action, method_index):
    kind = action[0]
    next_at = at + 6
    next_op = d[next_at] if p + 3 < insns else 0xFF
    has_result = next_op in (0x0a, 0x0b, 0x0c)
    dest = d[next_at + 1] if has_result else 0
    if kind == "nop":
        write_units(d, at, [0, 0, 0])
    elif kind == "retarget":
        _, new_key, drop = action
        struct.pack_into("<H", d, at + 2, method_index[new_key])
        if drop:
            if op >= 0x74:
                d[at + 1] = hi - drop
            else:
                d[at + 1] = (((hi >> 4) - drop) << 4) | (hi & 15)
    elif not has_result:
        write_units(d, at, [0, 0, 0])
    elif kind == "const":
        write_units(d, at, [0x13 | (dest << 8), action[1], 0, 0])
    elif kind == "wide":
        write_units(d, at, [0x16 | (dest << 8), action[1], 0, 0])
    elif kind == "result_arg":
        regs = registers(d, at, op, hi)
        write_units(d, at, [0x09, dest, regs[action[1]], 0])


with open(sys.argv[1], "rb") as source:
    patched, applied = patch(source.read())
with open(sys.argv[2], "wb") as target:
    target.write(patched)
for name, count in sorted(applied.items()):
    print(f"{count:3d} {name}")
print("patched call sites:", sum(applied.values()))
