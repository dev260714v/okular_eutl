# Privacy and data flow

The Okular dialog makes a byte-for-byte copy of the saved PDF in a private temporary
directory and checks the SHA-256 binding of the returned result. The helper does not
upload that PDF to a validation service. The temporary directory is removed when the dialog
is destroyed; this is normal file deletion, not a secure-erasure guarantee.

Outbound requests retrieve EU/national trusted lists, AIA issuer certificates and
OCSP/CRL evidence. OCSP requests may reveal certificate identifiers to the responder;
network services can also observe the requester's IP address. Network access is not anonymous.
The transport permits only public HTTP(S) destinations and checks DNS results at connection
time, including redirected connections. It does not use system proxies. This limits local
network access; it does not make third-party evidence endpoints anonymous or inherently safe.

The persistent user cache stores public validation evidence and reusable public signer
certificates. These certificates can contain names and organizations even though they do
not contain private keys. Cache directories are restricted to the current user.
The application does not write the PDF or a cached green/red verdict into this evidence cache.
Signer certificate reuse is limited to exact matches in the current document, a 30-day age,
and a stored maximum of 256 certificates / 16 MiB after successful writes. Expired and oldest
entries and orphaned list hints are pruned then. Manually remove the evidence cache to delete
retained certificates immediately; closing the dialog alone does not erase this cache.

Saved reports and logs can contain signer identities, certificate data, document hashes,
file paths, validation times and service URLs. Review and redact them before sharing.
Closing the dialog does not remove reports that the user explicitly saved.

This source repository intentionally excludes private documents, production test data,
validation reports, machine paths, credentials and generated development binaries.
Only synthetic examples and public EU trust-anchor certificates are included.
Use `scripts/audit-public.py` and manual review before committing new files.
