#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""A forráscsomag hordozhatóságának és ellenőrzőösszegeinek regressziós tesztjei."""
from contextlib import redirect_stdout
import gzip
import hashlib
import io
import os
from pathlib import Path
import re
import runpy
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zlib

REPO = Path(__file__).resolve().parents[1]


class CsomagkeszitesTeszt(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="eutl-csomagteszt-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("scripts/prepare-source.py", "eutldialog.h", "okular-eutl.patch", "PKGBUILD", "okular-eutl-engine"):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / name, target)
        for name, data in {"backend/pom.xml": "<project/>\n", "backend/src/Example.java": "class Example {}\n",
                           "trust/fixture.txt": "mesterséges tesztadat\n", "tests/fixture.py": "pass\n"}.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(data)

    def prepare(self):
        with redirect_stdout(io.StringIO()):
            runpy.run_path(str(self.root / "scripts/prepare-source.py"), run_name="__main__")
        return (self.root / "engine-source.tar").read_bytes()

    def test_nem_fugg_a_tomoritokonyvtartol(self):
        # Sem zlib, sem zlib-ng, sem a Python gzip-alapbeállítása nem befolyásolhatja.
        with patch.object(gzip, "GzipFile", side_effect=AssertionError("gzip használata")), \
             patch.object(zlib, "compressobj", side_effect=AssertionError("zlib használata")):
            data = self.prepare()
        self.assertFalse((self.root / "engine-source.tar.gz").exists())
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
            self.assertEqual(set(archive.getnames()), {"backend/pom.xml", "backend/src/Example.java", "trust/fixture.txt", "tests/fixture.py"})
        recipe = (self.root / "PKGBUILD").read_text()
        sums = re.findall(r"'([^']+)'", re.search(r"sha256sums=\((.*?)\)", recipe, re.S).group(1))
        self.assertEqual(sums[3], hashlib.sha256(data).hexdigest())

    def test_a_metaadatok_es_forditasi_maradvanyok_nem_szamitanak(self):
        first = self.prepare()
        recipe = (self.root / "PKGBUILD").read_bytes()
        for path in self.root.rglob("*"):
            if path.is_file():
                path.chmod(0o700)
                os.utime(path, (1234567890, 1234567890))
        for name in ("backend/target/output.bin", "tests/__pycache__/fixture.pyc"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes("mesterséges fordítási maradvány".encode())
        self.assertEqual(first, self.prepare())
        self.assertEqual(recipe, (self.root / "PKGBUILD").read_bytes())

    def test_a_valodi_forrasmodositas_megvaltoztatja_az_ellenorzoosszeget(self):
        first = self.prepare()
        recipe = (self.root / "PKGBUILD").read_bytes()
        (self.root / "backend/src/Example.java").write_text("class Example { int value; }\n")
        second = self.prepare()
        self.assertNotEqual(first, second)
        self.assertNotEqual(recipe, (self.root / "PKGBUILD").read_bytes())
        with tarfile.open(fileobj=io.BytesIO(second), mode="r:") as archive:
            self.assertIn(b"int value", archive.extractfile("backend/src/Example.java").read())


if __name__ == "__main__":
    unittest.main()
