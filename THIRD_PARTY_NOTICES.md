# Külső összetevők és licencek

Ez a tároló független integrációt és javítást tartalmaz a **KDE Okularhoz**.
Nem hivatalos KDE-kiadás, és nem az Európai Bizottság hivatalos terméke.

- **Okular 26.08.2:** az eredeti forrás és annak szerzői jogi nyilatkozatai a KDE közreműködőihez tartoznak.
  [Eredeti forráskód](https://invent.kde.org/graphics/okular/-/tree/v26.08.2).
  A javítás megőrzi az eredeti fájlok licencelését. A teljes eredeti forrásfa nem része ennek a tárolónak.
- **Az Arch Linux Okular-csomagleírása:** a PKGBUILD az eredeti csomagleírás alapján készült.
  [Eredeti csomagleírás](https://gitlab.archlinux.org/archlinux/packaging/packages/okular).
- **Digital Signature Services (DSS) 6.5.1:** az ellenőrzés megvalósítása, LGPL-2.1 licenccel.
  [Eredeti forráskód](https://github.com/esig/dss/tree/6.5.1).
  A Maven-függőségek azonosítóit és verzióit a `backend/pom.xml` tartalmazza.
- A közvetett Maven-függőségek saját licencei érvényben maradnak.
  Ezek a fordításkor töltődnek le; JAR-fájljaik és a generált, egyesített binárisok nem kerülnek a tárolóba.
- A `trust/` könyvtárban **nyilvános tanúsítványok** találhatók, nem magánkulcsok.
  Szerepüket, rögzített ujjlenyomataikat és hivatalos forrásukat a [bizalmi leírás](docs/TRUST.md) ismerteti.

Az új integrációs kód licence `GPL-2.0-or-later`; ezt a [LICENSE](LICENSE) fájl és az SPDX-fejlécek jelzik.
A `LICENSE` a GNU GPL 2. változatának eredeti, angol licencszövegét tartalmazza;
ez a licencdokumentum nem a projekt saját használati útmutatója.
Az eredeti licencszöveget és szerzői jogi nyilatkozatokat nem fordítjuk át és nem módosítjuk.
Az összetevőnevek és a szabványos licencazonosítók eredeti alakjukban szerepelnek.
