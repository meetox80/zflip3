import pathlib
import sys
import zipfile

source, dex_dir, target = sys.argv[1:4]

with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w") as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename), compress_type=item.compress_type)
    for dex in sorted(pathlib.Path(dex_dir).glob("*.dex")):
        zout.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
