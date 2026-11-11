import struct
import sys

ROW_OFFSET = int(sys.argv[2])
NAME = b"com.sec.imsservice"
SYSTEM_ID = 1000

data = open(sys.argv[1], "rb").read()
phoff, phentsize, phnum = struct.unpack_from("<QHH", data, 0x20)[0], struct.unpack_from("<H", data, 0x36)[0], struct.unpack_from("<H", data, 0x38)[0]
segments = []
for index in range(phnum):
    kind, _, offset, vaddr, _, filesz = struct.unpack_from("<IIQQQQ", data, phoff + index * phentsize)
    if kind == 1:
        segments.append((offset, vaddr, filesz))


def file_offset(address):
    for offset, vaddr, filesz in segments:
        if vaddr <= address < vaddr + filesz:
            return offset + address - vaddr
    raise SystemExit("address outside the file")


expected, _, pointer = struct.unpack_from("<iiQ", data, ROW_OFFSET)
start = file_offset(pointer)
name = data[start:data.index(b"\0", start)]
if expected != SYSTEM_ID or name != NAME:
    raise SystemExit("caller table row moved: id %d name %r" % (expected, name))
print("multiclientd caller row checked")
