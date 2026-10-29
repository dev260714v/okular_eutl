#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Helyi forráscsomag készítése megismételhető, személyes adat nélküli metaadatokkal."""
from pathlib import Path
import hashlib
import io
import re
import tarfile

root = Path(__file__).resolve().parents[1]
# Az olvasható fejléc az elsődleges; a telepíthető javításnak pontosan ezt kell tartalmaznia.
header = (root / "eutldialog.h").read_bytes()
assert header.endswith(b"\n"), "eutldialog.h must end in a newline"
blob = hashlib.sha1(b"blob " + str(len(header)).encode() + b"\0" + header).hexdigest()[:7]
lines = header.decode().splitlines(keepends=True)
new_file = ("diff --git a/part/eutldialog.h b/part/eutldialog.h\nnew file mode 100644\n"
            f"index 0000000..{blob}\n--- /dev/null\n+++ b/part/eutldialog.h\n@@ -0,0 +1,{len(lines)} @@\n"
            + "".join("+" + line for line in lines))
patch = root / "okular-eutl.patch"
old_patch = patch.read_text()
assert old_patch.startswith("diff --git a/part/eutldialog.h b/part/eutldialog.h\n")
next_file = old_patch.index("diff --git ", 1)
patch.write_text(new_file + old_patch[next_file:])
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
# A kis csomag tömörítetlen: a zlib és a zlib-ng eltérő bájtsorozatot állíthat elő
# ugyanabból a forrásból, ami gépenként módosítaná a PKGBUILD ellenőrzőösszegét.
(root / "engine-source.tar").write_bytes(buffer.getvalue())
recipe = root / "PKGBUILD"
text = recipe.read_text()
original = re.search(r"sha256sums=\((.*?)\)", text, re.S)
checksums = re.findall(r"'([^']+)'", original.group(1))
assert len(checksums) == 5, "Unexpected PKGBUILD source layout"
for index, name in enumerate(("okular-eutl.patch", "engine-source.tar", "okular-eutl-engine"), start=2):
    checksums[index] = hashlib.sha256((root / name).read_bytes()).hexdigest()
replacement = "sha256sums=(" + "\n            ".join(repr(value) for value in checksums) + ")"
recipe.write_text(text[:original.start()] + replacement + text[original.end():])
print("Az engine-source.tar elkészült, a PKGBUILD ellenőrzőösszegei frissítve.")
