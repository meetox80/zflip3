import sys

OLD = b"\0/proc/net/if_inet6\0"
NEW = b"\0/proc/self/fd/987\0\0"

source, target = sys.argv[1], sys.argv[2]
data = open(source, "rb").read()
if len(OLD) != len(NEW):
    raise SystemExit("replacement changes the string length")
if data.count(OLD) != 1:
    raise SystemExit("expected one address file path, found " + str(data.count(OLD)))
open(target, "wb").write(data.replace(OLD, NEW))
print("patched address file path")
