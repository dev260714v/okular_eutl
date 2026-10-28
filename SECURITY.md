# Security

This is an experimental local validation integration, not a qualified validation service.
The currently maintained integration targets Okular 26.08.2 and engine 0.1.2.

If GitHub private vulnerability reporting is enabled for this repository, use the
Security tab's private reporting option. Do not disclose an exploitable issue together
with personal documents, real certificates, tokens or private keys in a public issue.
A separate private contact address is not configured in this source distribution.

Areas requiring particular care include EU/LOTL trust anchors, trusted-list signature
checks, revocation freshness, request handling, PDF revisions and cache isolation.
A successful validation is scoped to the exact document hash and the available evidence.
