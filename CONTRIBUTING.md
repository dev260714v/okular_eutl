# Contributing

Use synthetic documents and anonymous reproduction steps. Never add production PDFs,
real signers' certificate dumps, private keys, validation reports or personal machine paths.

1. Run `python3 scripts/audit-public.py` and inspect every staged file.
2. Run `python3 tests/verify-trust.py`.
3. Run `mvn -B -ntp -f backend/pom.xml verify` with a JDK 17 or later.
4. After engine, test or trust changes, run `python3 scripts/prepare-source.py`.
5. If modifying `eutldialog.h`, update the matching new-file section in `okular-eutl.patch`.
   Test that the patch still applies to the exact supported upstream source.

Changes to trust-list acceptance, revocation freshness, file hashes or verdict aggregation
need regression tests. Never make missing evidence, an untrusted certificate or an unknown
result display as a successful validation. Never promote a cached signer to a trust anchor.

UI-only tests can be compiled with Qt6 development packages:

```sh
c++ -std=c++17 -fPIC -I. tests/ui-smoke.cpp $(pkg-config --cflags --libs Qt6Widgets) -o /tmp/okular-eutl-ui-test
QT_QPA_PLATFORM=offscreen /tmp/okular-eutl-ui-test
```

The Java tests and offscreen dialog tests do not replace a full patched-Okular build
and manual desktop test. Describe the checks you actually ran in the pull request.
