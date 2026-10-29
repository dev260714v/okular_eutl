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

# Sparse, structurally valid PDFs exercise real parsing/hash paths above the old
# limit and at the DSS 2 GiB - 1 byte boundary with the same 768 MiB heap as the installed launcher.
def sparse_pdf(path, size):
    with path.open("wb") as stream:
        stream.write(b"%PDF-1.4\n")
        offsets = [0]
        for number, value in enumerate((b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [] /Count 0 >>"), 1):
            offsets.append(stream.tell())
            stream.write(f"{number} 0 obj\n".encode() + value + b"\nendobj\n")
        # Unreferenced padding stream: PDFBox must not load it into a byte array.
        offsets.append(stream.tell())
        stream.write(b"3 0 obj\n<< /Length 4 0 R >>\nstream\n")
        start = stream.tell()
        stream.seek(size - 512)
        end = stream.tell()
        stream.write(b"\nendstream\nendobj\n")
        offsets.append(stream.tell())
        stream.write(f"4 0 obj\n{end-start}\nendobj\n".encode())
        xref = stream.tell()
        stream.write(b"xref\n0 5\n0000000000 65535 f \n")
        for offset in offsets[1:]:
            stream.write(f"{offset:010d} 00000 n \n".encode())
        stream.write(f"trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
        stream.write(b" " * (size-stream.tell()))
    assert path.stat().st_size == size

with tempfile.TemporaryDirectory(prefix="eutl-large-cli-") as temp:
    directory = Path(temp)
    for size in (256 * 1024**2 + 1, 2 * 1024**3 - 1, 2 * 1024**3, 2 * 1024**3 + 1):
        input_file = directory / "large.pdf"
        sparse_pdf(input_file, size)
        output = directory / str(size)
        output.mkdir()
        completed = subprocess.run(["java", "-Xmx768m", "-jar", str(jar), str(input_file), str(output), str(root / "trust")],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=360)
        result = json.loads((output / "result.json").read_text())
        if size < 2 * 1024**3:
            assert completed.returncode == 0, completed.stderr.decode(errors="replace")
            assert result["status"] == "NO_SIGNATURES", result
            assert result["fileSize"] == size
            with input_file.open("rb") as stream:
                expected_hash = hashlib.file_digest(stream, "sha256").hexdigest()
            assert result["sha256"] == expected_hash
        else:
            assert completed.returncode == 1 and result["status"] == "ERROR", result
            assert "2 GiB" in result["error"]
        print(f"large input {size}: OK")
