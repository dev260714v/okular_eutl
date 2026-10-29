#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Exercise the actual shaded engine process and JSON/file hash contract offline."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

jar = Path(sys.argv[1]).resolve()
root = Path(__file__).resolve().parents[1]
pdf = bytearray(b"%PDF-1.4\n")
offsets = [0]
for number, value in enumerate((b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [] /Count 0 >>"), 1):
    offsets.append(len(pdf))
    pdf.extend(f"{number} 0 obj\n".encode() + value + b"\nendobj\n")
xref = len(pdf)
pdf.extend(b"xref\n0 3\n0000000000 65535 f \n")
for offset in offsets[1:]:
    pdf.extend(f"{offset:010d} 00000 n \n".encode())
pdf.extend(f"trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())

with tempfile.TemporaryDirectory(prefix="eutl-cli-") as temp:
    directory = Path(temp)
    for name, data, state, code in (("unsigned", bytes(pdf), "NO_SIGNATURES", 0),
                                    ("malformed", b"%PDF-1.7\nnot a PDF", "ERROR", 1)):
        input_file = directory / (name + ".pdf")
        output = directory / name
        output.mkdir()
        input_file.write_bytes(data)
        completed = subprocess.run(["java", "-Xmx768m", "-jar", str(jar), str(input_file), str(output), str(root / "trust")],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        result = json.loads((output / "result.json").read_text())
        assert completed.returncode == code, completed.stderr.decode(errors="replace")
        assert result["status"] == state, result
        assert result["sha256"] == hashlib.sha256(data).hexdigest(), "file/result hash mismatch"
        assert result["documentCoverage"]["status"] == "UNKNOWN", "unsigned or broken input cannot have full coverage"
        print(name + ": OK")
