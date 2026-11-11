import struct
import sys

CHUNK_STRING_POOL = 0x0001
CHUNK_START_ELEMENT = 0x0102
CHUNK_END_ELEMENT = 0x0103
ATTRIBUTE_SIZE = 20
POOL_HEADER = 28
TYPE_STRING = 0x03
TYPE_BOOLEAN = 0x12
REMOVE_ELEMENTS = {"uses-library", "protected-broadcast"}
REMOVE_ATTRIBUTES = {"sharedUserId", "persistent"}
REQUEST_EXTRA = [
    "android.permission.READ_PHONE_STATE",
    "android.permission.DUMP",
    "android.permission.UPDATE_DEVICE_STATS",
    "android.permission.READ_PRECISE_PHONE_STATE",
    "android.permission.READ_ACTIVE_EMERGENCY_SESSION",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.READ_NETWORK_USAGE_HISTORY",
    "android.permission.CONNECTIVITY_INTERNAL",
    "android.permission.RECEIVE_DATA_ACTIVITY_CHANGE",
    "android.permission.MODIFY_AUDIO_ROUTING",
    "android.permission.CONTROL_LOCATION_UPDATES",
    "android.permission.LOCATION_HARDWARE",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.REAL_GET_TASKS",
]
ABSENT_PROVIDER_CLASS = "dev.flip3.ims.AbsentProvider"
ABSENT_AUTHORITIES = ";".join([
    "com.sec.knox.provider2",
    "com.samsung.android.mdec.provider.setting",
    "iwlansettings",
    "com.android.mms.csc.PreferenceProvider",
    "com.ims.dm.ContentProvider",
    "com.samsung.android.messaging.common.provider.WithAppProvider",
    "com.samsung.android.samsungaccount.accountmanagerprovider",
    "com.samsung.jansky.cab.provider",
    "com.samsung.jansky.provider",
    "com.samsung.nsds.provider",
    "com.samsung.vvm",
    "com.skt.prod.dialer.sktincallscreen.provider",
    "im",
    "bin_ft",
    "bin_im",
    "bin_mms",
    "bin_sms",
    "spam_im",
    "spammms",
    "spamsms",
])


def read_pool(data, at):
    count = struct.unpack_from("<I", data, at + 8)[0]
    flags = struct.unpack_from("<I", data, at + 16)[0]
    strings_start = struct.unpack_from("<I", data, at + 20)[0]
    header = struct.unpack_from("<H", data, at + 2)[0]
    utf8 = bool(flags & 0x100)
    result = []
    for index in range(count):
        offset = struct.unpack_from("<I", data, at + header + index * 4)[0]
        p = at + strings_start + offset
        if utf8:
            first = data[p]
            p += 2 if first & 0x80 else 1
            length = data[p]
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            result.append(data[p:p + length].decode("utf-8", "replace"))
        else:
            length = struct.unpack_from("<H", data, p)[0]
            p += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            result.append(data[p:p + length * 2].decode("utf-16-le", "replace"))
    return result


def encode_length8(value):
    return bytes([value]) if value < 0x80 else bytes([(value >> 8) | 0x80, value & 0xFF])


def encode_string(text, utf8):
    if utf8:
        raw = text.encode("utf-8")
        return encode_length8(len(text)) + encode_length8(len(raw)) + raw + b"\0"
    return struct.pack("<H", len(text)) + text.encode("utf-16-le") + b"\0\0"


def extend_pool(chunk, additions):
    _, header, size, count, styles, flags, strings_start, styles_start = struct.unpack_from("<HHIIIIII", chunk, 0)
    if styles or styles_start:
        raise SystemExit("string pool with styles is not supported")
    utf8 = bool(flags & 0x100)
    offsets = list(struct.unpack_from("<%dI" % count, chunk, header))
    data = bytearray(chunk[strings_start:size])
    for text in additions:
        offsets.append(len(data))
        data += encode_string(text, utf8)
    data += b"\0" * (-len(data) % 4)
    total = len(offsets)
    start = POOL_HEADER + total * 4
    result = bytearray(struct.pack("<HHIIIIII", 0x0001, POOL_HEADER, start + len(data), total, 0, flags, start, 0))
    result += struct.pack("<%dI" % total, *offsets)
    result += data
    return result


def walk(data):
    position = struct.unpack_from("<H", data, 2)[0]
    while position < len(data):
        kind, _, size = struct.unpack_from("<HHI", data, position)
        yield kind, position, size
        position += size


def attributes(data, at):
    count = struct.unpack_from("<H", data, at + 28)[0]
    start = at + 16 + struct.unpack_from("<H", data, at + 24)[0]
    return [start + index * ATTRIBUTE_SIZE for index in range(count)]


def element_name(data, at, strings):
    return strings[struct.unpack_from("<I", data, at + 20)[0]]


def name_attribute(data, at, strings):
    for attribute in attributes(data, at):
        if strings[struct.unpack_from("<I", data, attribute + 4)[0]] == "name":
            return attribute
    return None


source = open(sys.argv[1], "rb").read()
strings = []
declared = []
requested = set()
template_start = template_end = None
pending = None
provider_start = provider_end = None
for kind, at, size in walk(source):
    if kind == CHUNK_STRING_POOL:
        strings = read_pool(source, at)
    elif kind == CHUNK_START_ELEMENT:
        name = element_name(source, at, strings)
        if name == "provider" and provider_start is None:
            provider_start = source[at:at + size]
        attribute = name_attribute(source, at, strings)
        if attribute is None:
            continue
        value = strings[struct.unpack_from("<I", source, attribute + 8)[0]]
        if name == "permission":
            declared.append(value)
        elif name == "uses-permission":
            requested.add(value)
            if template_start is None and len(attributes(source, at)) == 1:
                pending = source[at:at + size]
    elif kind == CHUNK_END_ELEMENT:
        if pending is not None:
            template_start, template_end = pending, source[at:at + size]
            pending = None
        if provider_end is None and element_name(source, at, strings) == "provider":
            provider_end = source[at:at + size]
if template_start is None:
    raise SystemExit("no single attribute uses-permission to copy")
if provider_start is None or provider_end is None:
    raise SystemExit("no provider to copy")

wanted = []
for name in declared + REQUEST_EXTRA:
    if name not in requested and name not in wanted:
        wanted.append(name)
appended = [name for name in wanted + [ABSENT_PROVIDER_CLASS, ABSENT_AUTHORITIES] if name not in strings]
index_of = {name: index for index, name in reversed(list(enumerate(strings + appended)))}


def absent_provider():
    start = bytearray(provider_start)
    first = 16 + struct.unpack_from("<H", start, 24)[0]
    kept = bytearray()
    for attribute_at in attributes(start, 0):
        attribute = bytearray(start[attribute_at:attribute_at + ATTRIBUTE_SIZE])
        attribute_name = strings[struct.unpack_from("<I", attribute, 4)[0]]
        if attribute_name == "name":
            value = index_of[ABSENT_PROVIDER_CLASS]
        elif attribute_name == "authorities":
            value = index_of[ABSENT_AUTHORITIES]
        elif attribute_name == "exported":
            struct.pack_into("<I", attribute, 8, 0xFFFFFFFF)
            struct.pack_into("<B", attribute, 15, TYPE_BOOLEAN)
            struct.pack_into("<I", attribute, 16, 0)
            kept += attribute
            continue
        else:
            continue
        struct.pack_into("<I", attribute, 8, value)
        struct.pack_into("<B", attribute, 15, TYPE_STRING)
        struct.pack_into("<I", attribute, 16, value)
        kept += attribute
    if len(kept) != 3 * ATTRIBUTE_SIZE:
        raise SystemExit("provider template lacks name, exported or authorities")
    start = start[:first] + kept
    struct.pack_into("<H", start, 28, 3)
    struct.pack_into("<I", start, 4, len(start))
    return start + provider_end

out = bytearray(source[:struct.unpack_from("<H", source, 2)[0]])
removed_elements = 0
removed_attributes = 0
injected = False
for kind, at, size in walk(source):
    chunk = bytearray(source[at:at + size])
    if kind == CHUNK_STRING_POOL:
        out += extend_pool(chunk, appended)
        continue
    if kind in (CHUNK_START_ELEMENT, CHUNK_END_ELEMENT):
        name = element_name(source, at, strings)
        if name in REMOVE_ELEMENTS:
            removed_elements += 1
            continue
        if kind == CHUNK_END_ELEMENT and name == "application":
            out += absent_provider()
        if kind == CHUNK_START_ELEMENT:
            if name == "application" and not injected:
                for permission in wanted:
                    start = bytearray(template_start)
                    attribute = attributes(start, 0)[0]
                    struct.pack_into("<I", start, attribute + 8, index_of[permission])
                    struct.pack_into("<B", start, attribute + 15, TYPE_STRING)
                    struct.pack_into("<I", start, attribute + 16, index_of[permission])
                    out += start + template_end
                injected = True
            count = struct.unpack_from("<H", chunk, 28)[0]
            first = 16 + struct.unpack_from("<H", chunk, 24)[0]
            kept = bytearray()
            for index in range(count):
                attribute = chunk[first + index * ATTRIBUTE_SIZE:first + (index + 1) * ATTRIBUTE_SIZE]
                attribute_name = struct.unpack_from("<I", attribute, 4)[0]
                if strings[attribute_name] in REMOVE_ATTRIBUTES:
                    removed_attributes += 1
                else:
                    kept += attribute
            chunk = chunk[:first] + kept
            struct.pack_into("<H", chunk, 28, len(kept) // ATTRIBUTE_SIZE)
            struct.pack_into("<I", chunk, 4, len(chunk))
    out += chunk
if not injected:
    raise SystemExit("manifest has no application element")
struct.pack_into("<I", out, 4, len(out))
open(sys.argv[2], "wb").write(out)
open(sys.argv[3], "w").write("".join(name + "\n" for name in wanted))
print("removed elements:", removed_elements // 2, "attributes:", removed_attributes, "permissions requested:", len(wanted))
