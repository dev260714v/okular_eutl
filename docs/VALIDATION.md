# Validation status

The source-check workflow runs Maven verification on JDK 17 and 27, checks the public
source and pinned trust-anchor fingerprints, and checks deterministic packaging and
synchronization of the canonical dialog header with the installable Okular patch.
A separate CI job compiles and runs the Qt6 dialog test offscreen.
The built shaded JAR is also launched as a separate process against generated unsigned
and malformed PDFs to verify exit status, JSON status and the input hash contract.

The Java suite covers trusted-list acceptance, cache expiry/corruption/concurrency,
exact-certificate lookup hints, bounded non-trusting certificate reuse, worker shutdown,
public-address filtering at connection time, redirect policy, and bounded HTTP bodies
(including chunked responses, OCSP POST and decompressed gzip).

The offline PDF integration suite generates real PDF signatures, a synthetic CA and
signer, signed CRLs and RFC3161 timestamps in memory. It checks:

- Valid signature and exact current-revision coverage.
- Changed signed bytes yielding `TOTAL_FAILED / HASH_FAILURE`, even with a cached signer.
- Revoked signer failing validation.
- Missing trusted certificate chain remaining indeterminate, despite a cached signer.
- A later unsigned PDF revision being reported as partial coverage.
- A second PDF from the same cached signer still receiving document validation.
- Real RFC3161 timestamp validation together with the PDF signature.

The test CA is supplied only by tests. It is never added to the application trust bundle.
All synthetic private keys and signed documents are generated during testing and are not
distributed as fixture files. The PDF suite uses the engine's report-mapping code, but does
not simulate the full EU LOTL/national-list publication and signing infrastructure.

The Qt6 test covers busy state, valid, invalid, indeterminate and mixed results, unsigned
input, malformed JSON, mismatched file hashes, cancellation, and missing/partial/unknown
current-revision coverage. Its helper process is synthetic; the Java suite checks actual
PDF cryptography separately.

These checks do not replace a full patched-Okular build and manual desktop test on the
target distribution, nor certify every PDF. Maintain and review EU trust configuration,
revocation freshness, historical validation times and qualified-signature interpretation.
Missing evidence must not become a successful or invalid verdict without grounds.
