# Okular EUTL

Local EU trusted-list signature validation integrated into **Okular 26.08.2**,
using the European Commission's **Digital Signature Services (DSS) 6.5.1**.

[Magyar leírás](README.hu.md) · [Trust configuration](docs/TRUST.md) · [Privacy](docs/PRIVACY.md) · [Contributing](CONTRIBUTING.md)

This is an **experimental, independent Okular patch and local Java helper**.
It requires rebuilding Okular; it is not a drop-in plugin. The current dialog is in Hungarian.
It is not an official KDE/European Commission product or a qualified validation service.

## Features

- Validate PDF/PAdES signatures and timestamps using authenticated EU/national trusted lists.
- Separate validity from qualification, including qualified signatures and advanced signatures.
- Green **✓ valid**, red **✗ invalid**, and yellow **⚠ indeterminate** results, per signer and overall.
- Busy indicator, elapsed time, cancellation and expandable technical details.
- Toolbar action and **Tools → EU-s aláírás-ellenőrzés…** menu entry.
- Suppress the generic forms notification when there are only signature fields;
  preserve real form, integrity, revocation and document-revision warnings.
- Save JSON and DSS XML reports together with an engine log.
- Cache trusted-list bytes, issuer certificates and fresh revocation evidence.
  Recognized signer certificates provide lookup hints for the relevant national lists.
- Keep the PDF on the local machine. Network access retrieves validation evidence, not the PDF.

## Supported configuration

| Component | Target |
|---|---|
| Desktop | CachyOS / Arch Linux, KDE Plasma, x86_64 |
| Okular | Exactly 26.08.2; patch and source checksum are version-specific |
| Java | JDK 17+ for building; Java 17+ for running |
| DSS | 6.5.1 |
| Input | Saved local PDFs, up to 256 MiB |

The full desktop integration has been exercised during development on the target setup.
The public-source checks cover the Java engine and source packaging; they do not certify
all PDFs, distributions or future Okular versions. Do not treat a successful result as
proof that every later PDF revision is covered by every signature.

## Build and install on CachyOS / Arch

Download or clone this repository and open a terminal in its root directory.

```sh
sudo pacman -Syu --needed base-devel cmake maven jdk-openjdk git
python3 scripts/prepare-source.py
makepkg -Csi
```

Run `makepkg` as your normal user. The package is named `okular-eutl`, provides
`okular`, and replaces/conflicts with the repository `okular` package.
The build detects an installed JDK 17+ even if the system default points at an older JRE.
The first Maven build requires internet access to download dependencies.

The official Okular tarball checksum and release signature are verified.
If the signing key is missing, verify the full fingerprint against the
[KDE release page](https://kde.org/info/releases-26.08.2/) before importing it.
The expected release signer fingerprint is:

```text
D81C0CB38EB725EF6691C385BB463350D6EF31EF
```

For example, after verifying that fingerprint:

```sh
gpg --keyserver hkps://keys.openpgp.org --recv-keys D81C0CB38EB725EF6691C385BB463350D6EF31EF
makepkg -Csi
```

Close all Okular windows after installation and restart the application.
This custom package requires separate maintenance when upstream Okular changes.
To return to your distribution's package:

```sh
sudo pacman -S okular
```

## Use

1. Open a saved PDF with no unsaved modifications.
2. Click **EU-s ellenőrzés** in the toolbar or the signature notification,
   or use the Tools menu entry.
3. Wait for the verdict. **Megszakítás** cancels the running operation.
4. Expand **Részletek megjelenítése** for diagnostics, or choose **Jelentések mentése…**
   to save reports.

Existing customized toolbars may need the action added manually using
**Settings → Configure Toolbars…**. The action's internal name is `validate_eutl`.

Only an all-passed result is green. A failed signature makes the overall result red;
otherwise incomplete evidence makes it yellow. An unsigned PDF, malformed engine response
or mismatched file hash never becomes a successful signature result.

## Caching and native certificate storage

The default evidence cache is `$XDG_CACHE_HOME/okular-eutl/v1`, or
`~/.cache/okular-eutl/v1` when XDG_CACHE_HOME is unset. The first successful validation
populates it. Later documents from the same certificate can reuse evidence and national-list
lookup hints, but **each new document and signature is still verified**.

- Current trusted-list bytes: up to six hours, bounded by their signed `NextUpdate`.
- National lists and LOTL: their signatures and trust relationships are checked again on use.
- Issuer-certificate downloads: up to 30 days; certificate validity is still evaluated by DSS.
- OCSP/CRL evidence: reused only while sufficiently fresh under the configured DSS policy.
- Previously valid signer certificates: auxiliary chain-building data, never unconditional trust anchors.

This integration does **not** modify Poppler's native NSS database, Firefox's store or
system trust settings. The native signature panel and the EU validation dialog use
different verification paths. Importing a certificate into NSS would not preserve its
EU qualification or eliminate future revocation checks.

Caching reduces repeated work, but does not promise instantaneous or fully offline validation.
A short-lived OCSP response may need refreshing between documents. Performance counters
are included in the JSON report. Close Okular before manually removing the cache directory
if a clean online validation is needed.

## Engine development and tests

```sh
python3 tests/verify-trust.py
mvn -B -ntp -f backend/pom.xml verify
```

The public Maven coordinates and Java namespace are `org.okulareutl`; the shaded output is
`backend/target/okular-eutl-0.1.2.jar`. Generated binaries are not tracked in git.

The unit suite covers trusted-list acceptance, cache expiry/corruption/concurrency,
certificate-bound lookup hints, non-trusting certificate reuse and worker shutdown.
A synthetic Qt6 dialog smoke test is documented in [CONTRIBUTING.md](CONTRIBUTING.md).
See [validation notes](docs/VALIDATION.md) for the limits of these checks.

To update only the engine of an already installed integration, build it first, then:

```sh
sudo install -m 644 backend/target/okular-eutl-0.1.2.jar /usr/share/okular-eutl/engine.jar
sudo install -m 755 okular-eutl-engine /usr/bin/okular-eutl-engine
```

Manual engine replacement is overwritten by a later package installation.
After modifying backend, tests or trust data, rerun `scripts/prepare-source.py` before `makepkg`.

## Limitations

- Local saved PDF/PAdES input only; no ASiC/XAdES or standalone CAdES UI.
- Password-protected PDFs have no password-prompt integration.
- Five-minute UI timeout; 768 MiB JVM maximum heap.
- No promise of validation when current trust or revocation evidence is unavailable.
- Some detailed reasons retain DSS's original terminology.
- DSS's filesystem revocation-cache implementation is documented upstream as experimental.
- Trust anchors and their official publication must be maintained as EU material changes.

## License and upstream

New integration code: **GPL-2.0-or-later**. See [LICENSE](LICENSE) and
[third-party notices](THIRD_PARTY_NOTICES.md).

- [Okular source](https://invent.kde.org/graphics/okular/-/tree/v26.08.2)
- [DSS source](https://github.com/esig/dss/tree/6.5.1)
- [DSS documentation](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html)

Bug reports and pull requests should contain only synthetic or carefully redacted data.
Do not upload production signed documents or validation reports to public issues.
