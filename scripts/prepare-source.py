#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Create the local PKGBUILD source bundle with reproducible, anonymous metadata."""
from pathlib import Path
import gzip
import hashlib
import io
import re
import tarfile

root = Path(__file__).resolve().parents[1]
buffer = io.BytesIO()
with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
    for name in ("backend", "trust", "tests"):
        for path in sorted((root / name).rglob("*")):
            relative = path.relative_to(root)
            if not path.is_file() or path.is_symlink() or "target" in relative.parts or "__pycache__" in relative.parts:
                continue
            info = tarfile.TarInfo(relative.as_posix())
            data = path.read_bytes()
            info.size = len(data)
            info.mode = 0o755 if path.suffix in (".py", ".sh") else 0o644
            info.mtime = info.uid = info.gid = 0
            info.uname = info.gname = ""
            archive.addfile(info, io.BytesIO(data))
with (root / "engine-source.tar.gz").open("wb") as output:
    with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as compressed:
        compressed.write(buffer.getvalue())
recipe = root / "PKGBUILD"
text = recipe.read_text()
original = re.search(r"sha256sums=\((.*?)\)", text, re.S)
checksums = re.findall(r"'([^']+)'", original.group(1))
assert len(checksums) == 5, "Unexpected PKGBUILD source layout"
for index, name in enumerate(("okular-eutl.patch", "engine-source.tar.gz", "okular-eutl-engine"), start=2):
    checksums[index] = hashlib.sha256((root / name).read_bytes()).hexdigest()
replacement = "sha256sums=(" + "\n            ".join(repr(value) for value in checksums) + ")"
recipe.write_text(text[:original.start()] + replacement + text[original.end():])
print("Prepared engine-source.tar.gz and updated PKGBUILD checksums.")
