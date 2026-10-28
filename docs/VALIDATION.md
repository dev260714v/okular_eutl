# Validation status

The public engine is based on a development version with 29 passing unit tests covering
trusted-list validity, persistent-cache behavior, exact-certificate lookup hints,
untrusted certificate reuse, concurrency and worker shutdown.

During private development, original and byte-modified signed PDF samples were compared:
valid samples retained their DSS validity/qualification results; changing signed bytes
produced `TOTAL_FAILED / HASH_FAILURE` even with a populated evidence cache.
Those documents, signer identities, certificate dumps, hashes and generated reports are
not distributed with this repository.

A Qt6 offscreen smoke test covers valid, invalid, indeterminate and mixed results,
unsigned input, malformed JSON, mismatched file hashes and cancellation. It uses a synthetic
helper process, not a real certificate authority. See `CONTRIBUTING.md` for the command.

CI checks the Java engine and source packaging. It does not perform a complete KDE desktop
build or certify the behavior of every PDF. A full patched-Okular build and manual test on
the target distribution are still required after integration changes.

This project is experimental. In particular, maintain and review trust configuration,
revocation freshness, historical validation times, PDF revisions and any interpretation
of qualified-signature status. Missing evidence must remain indeterminate rather than
being converted to either a successful or an invalid signature result without grounds.
