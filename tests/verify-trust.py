#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
import base64
import hashlib
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
allowed = {line.strip() for line in (root / 'trust/allowed-sha256.txt').read_text().splitlines()
           if line.strip() and not line.startswith('#')}
pem = (root / 'trust/lotl-anchors.pem').read_text()
blocks = re.findall(r'-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----', pem)
assert blocks, 'No trust anchors'
seen = set()
for block in blocks:
    der = base64.b64decode(re.sub(r'\s+', '', block), validate=True)
    digest = hashlib.sha256(der).hexdigest()
    assert digest in allowed, f'Unrecognized trust anchor: {digest}'
    assert digest not in seen, 'Duplicate anchor'
    seen.add(digest)
assert seen == allowed, 'The packaged trust bundle differs from the declared pins'
print(f'OK: {len(seen)} pinned LOTL anchors match OJ C/2026/1944')
