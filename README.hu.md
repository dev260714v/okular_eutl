# Okular EUTL

Az **Okular 26.08.2-be integrált, helyben futó EU-s aláírás-ellenőrzés**, az Európai
Bizottság DSS 6.5.1 könyvtárával. Az ellenőrzőablak magyar nyelvű.

[English README](README.md) · [Adatkezelés](docs/PRIVACY.md) · [Bizalmi alap](docs/TRUST.md)

Kísérleti, független fejlesztés: Okular-javítás és helyi Java ellenőrzőmotor.
Nem bemásolható plugin: a teljes felülethez az Okulart újra kell fordítani.
Nem hivatalos KDE-termék, és nem minősített validálási szolgáltatás.

## Mit tud?

- PDF-aláírások és időbélyegek ellenőrzése hitelesített EU-s és tagállami bizalmi listákkal.
- Külön érvényességi eredmény és minősítés, aláírónként és összesítve.
- Zöld **✓ ÉRVÉNYES**, piros **✗ ÉRVÉNYTELEN**, sárga **⚠ NEM ELLENŐRIZHETŐ**.
- Animált folyamatjelző, eltelt idő és megszakítás.
- Eszköztárgomb és **Eszközök → EU-s aláírás-ellenőrzés…** menüpont.
- Menthető jelentések, külön megnyitható technikai részletek.
- Tartós gyorsítótár a bizonyítékokhoz; ismert aláírónál célzott tagállamilista-keresés.

A PDF a számítógépen marad. A hálózati kérések az ellenőrzéshez szükséges bizalmi
listákra, tanúsítványokra és visszavonási adatokra vonatkoznak.

## Telepítés CachyOS / Arch Linux alatt

A letöltött vagy klónozott repó gyökerében:

```sh
sudo pacman -Syu --needed base-devel cmake maven jdk-openjdk git
python3 scripts/prepare-source.py
makepkg -Csi
```

A `makepkg` parancsot normál felhasználóként futtasd. A csomag neve `okular-eutl`,
és lecseréli a tárolóból telepített `okular` csomagot. A csomagleírás automatikusan
keres JDK 17 vagy újabb környezetet. Az első fordításhoz internetkapcsolat szükséges.

Hiányzó kiadási aláírókulcs esetén az [angol útmutató](README.md#build-and-install-on-cachyos--arch)
tartalmazza a teljes ujjlenyomatot és az ellenőrzés lépéseit. Az aláírás-ellenőrzést ne kapcsold ki.

A telepítés végén zárj be minden Okular-ablakot, majd indítsd újra az alkalmazást.
A verzióhoz kötött javítást a későbbi Okular-kiadásokhoz külön karban kell tartani.

## Használat

Nyiss meg egy lemezre mentett PDF-et, majd kattints az **EU-s ellenőrzés** gombra.
Az első sikeres futás feltölti a gyorsítótárat; a következő fájloknál újrahasználja
a még aktuális bizonyítékokat. Minden új fájl aláírását külön ellenőrzi.

Ha egy testreszabott eszköztáron nem látszik a gomb:
**Beállítások → Eszköztárak beállítása…**, majd az EU-s aláírás-ellenőrzés hozzáadása.
A részletes adatok a **Részletek megjelenítése**, a jelentések a **Jelentések mentése…**
gombbal érhetők el.

Az összesítés csak akkor zöld, ha minden aláírás érvényes, és legalább egy érvényes
aláírás lefedi a jelenlegi PDF-változatot. A korábbi változatra vonatkozó aláírás
érvényes maradhat, de ha a jelenlegi fájl lefedettsége nem igazolt, az összesítés
sárga figyelmeztetést ad. A lefedettség aláírónként is látható.

## Gyorsítótár és tanúsítványok

A gyorsítótár alaphelye `~/.cache/okular-eutl/v1`; az `XDG_CACHE_HOME` beállítást
figyelembe veszi. Az aktuális bizalmi listák legfeljebb hat órán át, illetve saját
lejáratukig használhatók újra; az aláírásukat minden használatkor ellenőrzi.
A visszavonási válaszok saját frissességi korlátai továbbra is érvényesek.

A sikeresen ellenőrzött aláíró tanúsítványa segédadatként tárolódik. Ettől nem válik
feltétlen bizalmi ponttá. A program nem módosítja az Okular natív NSS-tárát, a Firefox
profilját vagy a rendszer bizalmi beállításait. A beépített aláíráspanel és az EU-s
ellenőrzőablak ezért eltérő eredményt is mutathat.

Hiányzó vagy lejárt bizonyíték esetén hálózati lekérésre lehet szükség.
Az ellenőrzés nem feltétlenül azonnali, és nincs garantált másodperces futási idő.
Csak a megnyitott dokumentumhoz tartozó mentett aláírói tanúsítványokat tölti be.
Ezek legfeljebb 30 napig használhatók újra; sikeres mentéskor a tárolt készletet
legfeljebb 256 tanúsítványra és 16 MiB-ra csökkenti, a legrégebbiek törlésével.

A hálózati céloknak nyilvános HTTP/HTTPS-címeknek kell lenniük. A belső, helyi és
fenntartott címeket a kapcsolat létrehozásakor is tiltja. Legfeljebb öt átirányítás
engedélyezett; HTTPS-ről HTTP-re visszalépés nincs. Rendszerszintű proxyt nem használ.
A válaszméretet olvasás és kitömörítés közben korlátozza: bizalmi listánál és CRL-nél
64 MiB, kibocsátói tanúsítványnál és OCSP-válasznál 1 MiB. A tiltott vagy túl nagy
bizonyíték nem használható fel; az eredmény ilyenkor hiányos lehet.

## Korlátok és fejlesztés

Helyi PDF/PAdES fájlok, legfeljebb 256 MiB méretig. Nincs ASiC/XAdES felület vagy
jelszóbekérés titkosított PDF-ekhez. Az ablak öt perc után megszakítja a futást.
Az érvényes aláírás nem bizonyítja önmagában, hogy a PDF összes későbbi változatát lefedi.

```sh
python3 tests/verify-trust.py
mvn -B -ntp -f backend/pom.xml verify
```

A részletes fordítási, motorfrissítési és tesztelési útmutató az [angol README-ben](README.md)
és a [CONTRIBUTING.md](CONTRIBUTING.md) fájlban található.
Az automatikus GitHub-ellenőrzés Java 17 és 27 alatt futtatja a teszteket, valamint
képernyő nélküli Qt6-felülettesztet végez. A PDF-es integrációs tesztek futás közben
generált kulcsokat, tanúsítványokat, visszavonási listákat és időbélyegeket használnak.

Valódi aláírt dokumentumot, tanúsítványdumpot, személyes adatot vagy teljes
validálási jelentést ne csatolj nyilvános hibajegyhez.

Licenc: **GPL-2.0-or-later**; a külső komponenseket a [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) sorolja fel.
