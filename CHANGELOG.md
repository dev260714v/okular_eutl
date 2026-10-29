# Changelog

## 0.1.3 — Network, coverage and cache hardening

- Restrict TL/AIA/CRL/OCSP transport to public HTTP(S) destinations, with DNS checks
  at connection time, bounded redirects and no HTTPS downgrades or system proxies.
- Bound response bodies during streaming and decompression; abort oversized/intermediate bodies.
- Show current PDF revision coverage separately from signature validity. The overall
  result is green only when a valid signature covers the current revision and all signatures pass.
- Load only matching signer certificates; enforce 30-day reuse and prune stored signers
  to 256 certificates / 16 MiB, removing expired and orphaned hints on successful writes.
- Restore GitHub checks and source exclusions missing from the initial public upload.
- Add offline signed-PDF/RFC3161/CRL integration tests, transport and cache regression tests,
  a shaded-engine CLI check and offscreen Qt6 tests in CI on Java 17/27.
- Synchronize the installable dialog patch automatically when preparing source packages.

## 0.1.2 — Initial public source release

- Integrated Hungarian-language EU signature validation dialog for patched Okular 26.08.2.
- Local DSS 6.5.1 engine with authenticated EU and national trusted lists.
- Separate validity and signature-qualification results.
- Busy indicator, elapsed time, cancellation, colored verdicts and collapsible details.
- Toolbar action and more precise form/signature notifications.
- Persistent evidence cache and certificate-bound trusted-list lookup hints.
- Explicit shutdown of trusted-list worker threads.
- Reusable public signer certificates remain adjunct evidence, not unconditional trust anchors.
- Source prepared for public distribution with neutral Java namespaces and no user documents,
  validation reports, machine paths or private development history.
