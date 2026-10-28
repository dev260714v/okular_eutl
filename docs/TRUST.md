# Trust configuration

DSS starts with the public LOTL-signing certificates in `trust/lotl-anchors.pem`.
The permitted SHA-256 fingerprints are pinned in `trust/allowed-sha256.txt` and checked
by `tests/verify-trust.py`.

The official publication URL is in `trust/oj-url.txt`:
<https://eur-lex.europa.eu/eli/C/2026/1944/oj>.
The included certificates were checked against the published fingerprints during development.
They are not the certificates of users, municipal organizations or production test signers.

The current EU LOTL is authenticated from those anchors. DSS follows its pivot mechanism
and authenticates the referenced national trusted lists. A list is eligible for synchronization
only when its download succeeded, its signature is valid and its issue/next-update interval
is current. Both DSS cache states `DESYNCHRONIZED` and `SYNCHRONIZED` represent usable
successful download data at different stages; neither bypasses those security checks.

Archived final UK list bytes can be cached, but an expired/non-current list does not
become a current trusted source. Known-signer lookup hints only select URLs already
advertised by the authenticated LOTL. They do not add new independent trust anchors.
If selected lists cannot establish a conclusive result, the engine retries a full search.

Issuer and previously valid signer certificates are auxiliary evidence. Revocation,
validity periods and EU qualification are evaluated by DSS for the new document.
Cached certificates are never promoted to the trusted-source collection.

Trust configuration requires maintenance. When official LOTL signing material changes,
verify the new official publication and fingerprints before changing these files, then
regenerate packaging checksums. Never copy a document's signing certificate into the
LOTL-anchor file merely to make a validation pass.
