# Third-party components

This repository contains an independent integration and a patch for **KDE Okular**.
It is not an official KDE release or an official European Commission product.

- **Okular 26.08.2**: upstream source and existing copyright notices remain with KDE contributors.
  Source: <https://invent.kde.org/graphics/okular/-/tree/v26.08.2>.
  The patch preserves the original files' licensing. The complete upstream tree is not vendored.
- **Arch Linux Okular packaging**: the PKGBUILD is adapted from the upstream packaging recipe.
  Source: <https://gitlab.archlinux.org/archlinux/packaging/packages/okular>.
- **Digital Signature Services (DSS) 6.5.1**: validation implementation, LGPL-2.1.
  Source: <https://github.com/esig/dss/tree/6.5.1>.
  Maven dependency coordinates and versions are in `backend/pom.xml`.
- Transitive Maven dependencies retain their own licenses. They are downloaded during builds;
  their JARs and generated shaded binaries are not committed to this repository.
- Files under `trust/` are **public certificates**, not private keys. Their role, pinned
  fingerprints and official source are documented in `docs/TRUST.md`.

New integration code uses `GPL-2.0-or-later`; see `LICENSE` and the SPDX headers.
