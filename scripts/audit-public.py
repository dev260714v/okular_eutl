#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Audit the exact tracked/exported source set, not ignored local build outputs."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
if (root / ".git").exists():
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    files = [root / name for name in names if name]
else:
    files = [p for p in root.rglob("*") if p.is_file() and not any(x in p.parts for x in ("target", ".git", "__pycache__")) and p.name not in {"engine-source.tar", "engine-source.tar.gz"}]
errors = []
for path in files:
    name = path.relative_to(root).as_posix()
    lower = name.lower()
    if path.is_symlink(): errors.append((name, "symbolic link")); continue
    if path.suffix.lower() in {".pdf", ".jar", ".class", ".zip", ".p12", ".pfx", ".key", ".jks", ".log", ".gz", ".tar"}:
        errors.append((name, "private input, credential or generated binary"))
    if path.name in {"result.json", "simple-report.xml", "detailed-report.xml", "diagnostic-data.xml", "etsi-report.xml"}:
        errors.append((name, "validation report"))
    data = path.read_bytes()
    if re.search(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----", data):
        errors.append((name, "private key"))
    # These patterns also protect contributors from publishing their own local home paths.
    if re.search(rb"/(?:home|Users|workspace)/[A-Za-z0-9_.-]+/", data):
        errors.append((name, "absolute personal/workspace path"))
    if re.search(rb"(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})", data):
        errors.append((name, "possible access token"))
    if lower.startswith("trust/") and path.suffix == ".pem" and b"BEGIN CERTIFICATE" not in data:
        errors.append((name, "unexpected trust-file format"))
if errors:
    for name, reason in errors: print(f"FAIL {name}: {reason}")
    sys.exit(1)
print(f"Public-source audit passed for {len(files)} files. Review free text manually as well.")
