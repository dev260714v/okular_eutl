# Okular EUTL

Az **Okular 26.08.2-be integrált, helyben futó EU-s aláírás-ellenőrzés**, az Európai
Bizottság **Digital Signature Services (DSS) 6.5.1** könyvtárával.

[Bizalmi beállítások](docs/TRUST.md) · [Adatvédelem](docs/PRIVACY.md) · [Közreműködés](CONTRIBUTING.md) · [Változásnapló](CHANGELOG.md)

Ez egy **kísérleti, független Okular-javítás és helyi Java ellenőrzőmotor**.
Használatához újra kell fordítani az Okulart; nem külön bemásolható bővítmény.
Az ellenőrzőablak és a projekt dokumentációja magyar nyelvű.
Nem hivatalos KDE- vagy európai bizottsági termék, és nem minősített validálási szolgáltatás.

## Funkciók

- PDF/PAdES-aláírások, beágyazott RFC 3161-időbélyegek és önálló PDF-dokumentum-időbélyegek ellenőrzése hitelesített EU-s és tagállami bizalmi listákkal.
- Aláírásonként elkülönített aláírói időközlés és szolgáltatói időbélyeg. Az időbélyeg jelenléte, érvényessége és minősített jellege külön adat.
- Az alapnézet csak az aláírás, az időbélyegzés és a jelenlegi PDF-lefedettség állapotát mutatja; a technikai adatok kinyithatók és exportálhatók.
- Külön érvényességi eredmény és minősítés, beleértve a minősített és a fokozott biztonságú aláírásokat.
- Aláírónkénti és összesített eredmény: zöld **✓ ÉRVÉNYES**, piros **✗ ÉRVÉNYTELEN**, sárga **⚠ NEM ELLENŐRIZHETŐ**.
- Animált folyamatjelző, eltelt idő, megszakítás és kinyitható technikai részletek.
- Eszköztárgomb és **Eszközök → EU-s aláírás-ellenőrzés…** menüpont.
- Az általános űrlapértesítés elhagyása, ha a PDF csak aláírásmezőket tartalmaz.
  A valódi űrlapokra, sértetlenségre, visszavonásra és dokumentumváltozatokra vonatkozó jelzések megmaradnak.
- JSON- és DSS XML-jelentések mentése a motor naplójával együtt.
- Bizalmi listák, kibocsátói tanúsítványok és aktuális visszavonási bizonyítékok közös, legfeljebb 256 MiB-os és 4096 adatfájlos gyorsítótára.
  Az ismert aláíró tanúsítványa segít kiválasztani az érintett tagállami listákat.
- A PDF a számítógépen marad; a hálózati kérések az ellenőrzési bizonyítékokat töltik le.

## Támogatott környezet

| Összetevő | Követelmény |
|---|---|
| Asztali környezet | CachyOS / Arch Linux, KDE Plasma, x86_64 |
| Okular | Pontosan 26.08.2; a javítás és a forrás ellenőrzőösszege verzióhoz kötött |
| Java | Fordításhoz JDK 17 vagy újabb; futtatáshoz Java 17 vagy újabb |
| DSS | 6.5.1 |
| Bemenet | Lemezre mentett helyi PDF, legfeljebb 2 GiB − 1 bájt (2 147 483 647 bájt) |

A teljes asztali integrációt a fejlesztés során a célkörnyezetben kipróbáltuk.
A nyilvános forrás ellenőrzései a Java-motorra és a csomagkészítésre terjednek ki;
nem igazolják minden PDF, disztribúció vagy későbbi Okular-verzió megfelelő működését.
Egy sikeres eredmény önmagában nem jelenti azt, hogy minden aláírás lefedi a PDF összes későbbi változatát.

## Időbélyegzés és lefedettség

Az ellenőrzés minden felismert időbélyeget vizsgál, akkor is, ha annak használata az adott
iratnál nem kötelező. A hiányzó időbélyeg külön figyelmeztetés, önmagában nem teszi
érvénytelenné a kriptográfiailag érvényes aláírást. A hibás vagy nem eldönthető időbélyeg
szintén külön eredményt kap. A szolgáltató nevét, időpontját és a DSS szerinti minősítést is kijelezzük.
Az aláírás minősített jellege nem jelenti automatikusan az időbélyeg minősített jellegét.

Az aláíró által megadott idő (`claimedSigningTime`) származhat a számítógép órájából;
ez önmagában nem független időigazolás. A JSON eredménysémája 2-es verziójú; a motor és a felület együtt frissítendő.
A `bestSignatureTime` a DSS belső validálási
időadata, időbélyeg hiányában az ellenőrzési idő is lehet; nem jelenítjük meg hiteles
aláírási időként. Az időbélyeg időpontja az általa védett adat addigi létezését igazolja,
nem feltétlenül az aláírás létrehozásának pontos pillanatát.

A jelenlegi PDF-változatot teljesen lefedő, érvényes dokumentum-időbélyeg is igazolhatja
a lefedettséget. Ez nem helyettesíti az aláíró aláírását és nem tesz érvényessé egy hibás
aláírást. Utólag, az időbélyeg után hozzáadott bájtokat továbbra is jelezzük.

## Nagy fájlok és korlátok

A legnagyobb bemenet **2 GiB − 1 bájt (2 147 483 647 bájt)**, ezt meghaladó fájlt a program elutasít.
Az egybájtos határ oka a DSS 6.5.1 PDF-változatkezelésének előjeles, 32 bites
bájttartomány-számítása; nem engedünk át olyan határértéket, amely túlcsordulhat. A lenyomat számítása
folyamatos olvasással, a munkapéldány készítése háttérszálon történik. A DSS/PDFBox
fájlalapú feldolgozást és ideiglenes lemezterületet használ; a teljes PDF-et nem töltjük
be egyetlen bájttömbbe. A PDF szerkezetétől függően továbbra is szükséges szabad memória,
és a munkapéldány, illetve az ideiglenes állományok számára a bemenet méretének többszöröse
lehet szükséges a háttértáron. A Java-heap alapértelmezett korlátja 768 MiB.
256 MiB felett az ellenőrzés időkorlátja 30 perc, kisebb PDF-eknél 5 perc;
a munkapéldány készítése és az ellenőrzés is megszakítható.

**A jelszóval védett, megnyitási jelszót igénylő PDF-ek ellenőrzését a program jelenleg
nem támogatja, akkor sem, ha a dokumentumot az Okularban már jelszóval megnyitották.**
A jelszó átadására vagy bekérésére nincs lehetőség.

## Fordítás és telepítés CachyOS / Arch Linux alatt

Töltsd le vagy klónozd ezt a tárolót, és nyiss terminált a gyökérkönyvtárában.

```sh
sudo pacman -Syu --needed base-devel cmake maven jdk-openjdk git
python3 scripts/prepare-source.py
makepkg -Csi
```

A `makepkg` parancsot normál felhasználóként futtasd. A csomag neve `okular-eutl`,
biztosítja az `okular` csomagot, és lecseréli a disztribúció `okular` csomagját.
A fordítás keres egy telepített JDK 17 vagy újabb környezetet akkor is, ha a rendszer
alapértelmezett Java-környezete egy régebbi JRE. Az első Maven-fordításhoz internetkapcsolat kell a függőségek letöltéséhez.

Az előkészítés tömörítetlen `engine-source.tar` motorforráscsomagot készít. Így a zlib és
zlib-ng eltérő tömörítési eredménye nem okoz gépenként eltérő ellenőrzőösszeget a `PKGBUILD` fájlban.
A régi `engine-source.tar.gz` már nem része a fordításnak; törölhető vagy figyelmen kívül hagyható.

A csomagkészítés ellenőrzi a hivatalos Okular-forrásarchívum ellenőrzőösszegét és kiadási aláírását.
Ha az aláírókulcs hiányzik, importálás előtt vesd össze a teljes ujjlenyomatot a
[KDE kiadási oldalával](https://kde.org/info/releases-26.08.2/).
A várt kiadási aláírókulcs ujjlenyomata:

```text
D81C0CB38EB725EF6691C385BB463350D6EF31EF
```

Az ujjlenyomat ellenőrzése után például így importálható:

```sh
gpg --keyserver hkps://keys.openpgp.org --recv-keys D81C0CB38EB725EF6691C385BB463350D6EF31EF
makepkg -Csi
```

A telepítés végén zárj be minden Okular-ablakot, majd indítsd újra az alkalmazást.
Az egyedi csomagot az Okular újabb kiadásaihoz külön karban kell tartani.
A disztribúció eredeti csomagjára így térhetsz vissza:

```sh
sudo pacman -S okular
```

## Használat

1. Nyiss meg egy mentett PDF-et, amelyben nincs mentetlen módosítás.
2. Kattints az eszköztár vagy az aláírásértesítés **EU-s ellenőrzés** gombjára,
   vagy válaszd az Eszközök menü megfelelő pontját.
3. Várd meg az eredményt. A **Megszakítás** leállítja a folyamatban lévő ellenőrzést.
4. A **Részletek megjelenítése** gombbal nyithatók meg a diagnosztikai adatok;
   a **Jelentések mentése…** gombbal menthetők a jelentések.

Korábban testreszabott eszköztár esetén kézzel is hozzá kellhet adni a műveletet:
**Beállítások → Eszköztárak beállítása…**. A művelet belső azonosítója `validate_eutl`.

Az összesítés csak akkor zöld, ha minden aláírás ellenőrzése sikeres, és legalább egy
érvényes aláírás lefedi a jelenlegi PDF-változatot. Az aláírás érvényessége és a jelenlegi
változat lefedettsége külön jelenik meg. A korábbi aláírások korábbi változatokra is vonatkozhatnak.
Az aláírás után hozzáadott bájtok vagy az ismeretlen lefedettség sárga összesítést eredményeznek;
ettől egy korábbi változat érvényes aláírása nem válik érvénytelenné.
Érvénytelen aláírás esetén az összesítés piros, hiányos bizonyíték esetén egyébként sárga.
Aláírás nélküli PDF, hibás motorválasz vagy eltérő fájllenyomat nem kaphat sikeres aláírás-ellenőrzési eredményt.

## Gyorsítótár és natív tanúsítványtárolás

A bizonyítékok gyorsítótára alapértelmezetten `$XDG_CACHE_HOME/okular-eutl/v1`,
az `XDG_CACHE_HOME` hiányában `~/.cache/okular-eutl/v1`. Az első sikeres ellenőrzés
feltölti a gyorsítótárat. Ugyanazzal a tanúsítvánnyal aláírt későbbi dokumentumoknál
újra felhasználhatók a bizonyítékok és a tagállami listák keresési segédadatai,
de **minden új dokumentum és aláírás külön ellenőrzésen megy át**.

- Aktuális bizalmi listák: legfeljebb hat órán át használhatók újra, az aláírt `NextUpdate` időpontjáig.
- Tagállami listák és LOTL: az aláírásukat és a bizalmi kapcsolataikat minden használatkor újra ellenőrzi a motor.
- Letöltött kibocsátói tanúsítványok: legfeljebb 30 napig tárolhatók újrafelhasználásra; érvényességüket továbbra is a DSS értékeli.
- OCSP/CRL-bizonyítékok: legfeljebb 5 percig tárolhatók újrafelhasználhatóan; a DSS ezen belül is ellenőrzi az érvényességet és a frissességet. Az OCSP-kulcs a URL és a teljes kérés lenyomata.
- Korábban érvényes aláírói tanúsítványok: kizárólag a tanúsítványlánc felépítését segítik, nem válnak feltétlen bizalmi ponttá.
  Csak az aktuális dokumentumhoz pontosan illeszkedő tanúsítványok töltődnek be; 30 nap után nem használhatók újra.
  Sikeres íráskor a motor a legrégebbi bejegyzések törlésével legfeljebb 256 tanúsítványra és 16 MiB-ra
  csökkenti a készletet, és törli az árván maradt listakeresési segédadatokat.

Az integráció nem módosítja a Poppler natív NSS-adatbázisát, a Firefox tanúsítványtárát
vagy a rendszer bizalmi beállításait. A natív aláíráspanel és az EU-s ellenőrzőablak
eltérő ellenőrzési folyamatot használ. Egy tanúsítvány NSS-be importálása nem őrizné meg
annak EU-s minősítését, és nem váltaná ki a későbbi visszavonás-ellenőrzéseket.

A gyorsítótár csökkenti az ismétlődő munkát, de nem garantál azonnali vagy teljesen hálózat nélküli ellenőrzést.
Egy rövid élettartamú OCSP-választ két dokumentum között is frissíteni kellhet.
A JSON-jelentés teljesítményadatokat is tartalmaz. Ha tiszta hálózati ellenőrzést szeretnél,
a gyorsítótár kézi törlése előtt zárd be az Okulart.

## A motor fejlesztése és tesztelése

```sh
python3 tests/verify-trust.py
python3 tests/test-packaging.py
mvn -B -ntp -f backend/pom.xml verify
python3 tests/engine-cli.py backend/target/okular-eutl-0.1.4.jar
```

A Maven-csoportazonosító és a Java-névtér `org.okulareutl`.
A függőségeket is tartalmazó kimeneti JAR: `backend/target/okular-eutl-0.1.4.jar`.
A generált bináris fájlok nem kerülnek a Git-tárolóba.

Az egységtesztek a bizalmi listák elfogadását, a gyorsítótár lejáratát, sérülését és párhuzamos
használatát, a tanúsítványhoz kötött keresési segédadatokat, a korlátozott és bizalmi pontot
nem létrehozó tanúsítvány-újrafelhasználást, a hálózati korlátozásokat, a folyamatos olvasás
közbeni méretkorlátot, a PDF-lefedettséget és a háttérszálak leállítását ellenőrzik.
A hálózat nélküli integrációs tesztek valódi aláírt PDF-eket, tanúsítványokat, CRL-eket és
RFC3161-időbélyegeket generálnak. Ezekkel érvényes, módosított, visszavont, hiányzó bizalmi
láncú, később módosított és ismétlődő aláírótól származó dokumentumokat vizsgálnak.
A GitHub automatikus ellenőrzése Java 17 és 27 alatt futtatja a teszteket,
valamint a [közreműködési útmutatóban](CONTRIBUTING.md) ismertetett, képernyő nélküli Qt6-felülettesztet.
A vizsgálatok korlátait az [ellenőrzési leírás](docs/VALIDATION.md) részletezi.

Már telepített integrációnál csak a motor cseréjéhez először fordítsd le az új változatot, majd:

```sh
sudo install -m 644 backend/target/okular-eutl-0.1.4.jar /usr/share/okular-eutl/engine.jar
sudo install -m 755 okular-eutl-engine /usr/bin/okular-eutl-engine
```

A későbbi csomagtelepítés felülírja a kézzel lecserélt motort.
A motor, a tesztek vagy a bizalmi adatok módosítása után a `makepkg` előtt
futtasd újra a `scripts/prepare-source.py` szkriptet. Ez a felület fejlécét is beépíti a telepítendő javításba.

## Korlátok

- Csak lemezre mentett helyi PDF/PAdES-fájlok; nincs ASiC/XAdES- vagy külön CAdES-felület.
- Titkosított PDF-ekhez nincs beépített jelszóbekérés.
- A felület kisebb PDF-eknél öt, 256 MiB felett harminc perc után megszakítja az ellenőrzést; a JVM memóriakerete legfeljebb 768 MiB.
- A bizonyítékok címeinek HTTP(S)-t kell használniuk, és átirányításkor is kizárólag nyilvános címekre szabad feloldódniuk.
  A motor kapcsolódáskor tiltja a visszacsatolási, magán-, helyi kapcsolati, fenntartott és nem globális címeket.
  Legfeljebb öt átirányítás engedélyezett; HTTPS-ről HTTP-re visszalépés és nem egyértelmű POST-átirányítás nem.
  Rendszerszintű proxyt nem használ, mert a távoli névfeloldás megkerülhetné ezeket az ellenőrzéseket.
- A válaszméretkorlát a kitömörített adatok olvasása közben is érvényes:
  bizalmi listánál és CRL-nél 64 MiB, AIA-tanúsítványnál és OCSP-válasznál 1 MiB.
  A túl nagy vagy tiltott bizonyíték nem használható fel; az érvényességi eredményt ilyenkor
  is más hitelesített bizonyítékoknak kell megalapozniuk.
- Legfeljebb négy bizalmilista-feladat fut párhuzamosan.
- Aktuális bizalmi vagy visszavonási bizonyíték hiányában az ellenőrzés sikere nem garantálható.
- Egyes részletes indokok a DSS eredeti szakkifejezéseivel jelennek meg.
- A DSS saját, fájlrendszerben tárolt visszavonási gyorsítótárát annak fejlesztői kísérletiként írják le.
- Az EU-s aláírási adatok változásakor a bizalmi pontokat és hivatalos közzétételüket is karban kell tartani.

## Licenc és eredeti projektek

Az új integrációs kód licence: **GPL-2.0-or-later**. Az eredeti licencszöveg a
[LICENSE](LICENSE) fájlban, az összetevőkre vonatkozó magyar tájékoztatás a
[külső összetevők leírásában](THIRD_PARTY_NOTICES.md) található.
A licenc eredeti szövege, a szerzői jogi nyilatkozatok, a műszaki azonosítók és a komponensek nevei eredeti alakjukban szerepelnek.

- [Az Okular forráskódja](https://invent.kde.org/graphics/okular/-/tree/v26.08.2)
- [A DSS forráskódja](https://github.com/esig/dss/tree/6.5.1)
- [A DSS külső dokumentációja](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html)

Hibajegyhez és módosítási javaslathoz csak mesterséges tesztadatot vagy gondosan anonimizált
adatot csatolj. Valódi aláírt dokumentumot vagy teljes validálási jelentést ne tölts fel nyilvános hibajegyhez.

### Technikai többlet a PDF lezárása után

A `TECHNICAL_TAIL_V1` szabály kizárólag sikeresen érvényesített aláírás esetén
enged zöld eredményt kék információs jelzéssel. Az aláírt verzió szabályos
`startxref`/`%%EOF` lezárása után legfeljebb 4096 bájt állhat: PDF-üreskarakterek,
vagy egy UUID-alakú MIME-záróhatároló, opcionális `EOF` zárósorral. Ellenőrizzük
a `startxref` célját is. Tetszőleges szöveg, új PDF-verzió, objektum, ismeretlen
határoló és űrlap-megjelenítési változás nem tartozik a kivételbe.
A szabály nem általános vizuális vagy jogi azonosságvizsgálat: a véges nyelvtan
kizárja, hogy az elfogadott többlet új PDF-objektumot vagy hivatkozást tartalmazzon.
Az exportban a `documentCoverage.status` továbbra is `PARTIAL`, az
`assessment` értéke `TECHNICAL_ONLY`, a `technicalTail` pedig rögzíti a szabályt,
a típust, a bájtszámot és az aláírt verzió végét. Ismeretlen esetben marad a
figyelmeztetés. A program ebből nem von le automatikus joghatásbeli következtetést.
