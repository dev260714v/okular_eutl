# Változásnapló

## 0.1.4, 10. csomagkiadás – QStringBuilder-fordítás javítása

- A technikai összefoglaló azonosítója közvetlen QString-értékként kerül a JSON-objektumba; nem marad lusta QStringBuilder-kifejezés az inicializálólistában.
- A Qt6-felületteszt normál módban és az Okular/KDE QStringBuilder-beállításaival is fordul és fut.
- A generált Okular-javítás és a csomag ellenőrzőösszegei frissítve.

## 0.1.4, 9. csomagkiadás – Információs jelzések és hibaexport

- Az utólagos dokumentum-időbélyeggel lefedett PDF külön kék információs sávot kap, ha az érvényes aláírás csak korábbi változatra vonatkozik.
- A natív ellenőrzés általános hibái és befejezetlen állapotai információs sávban jelennek meg.
- Részletes, engedélylistával szűrt `summary.json` és `summary.txt` export nevek, tanúsítványtartalom, helyi útvonalak és szabad szöveges diagnosztika nélkül.
- Motorhiba, hibás válasz, összeomlás és időtúllépés után is exportálhatók a rendelkezésre álló diagnosztikai fájlok.
- A v2 gyorsítótár atomikusan tárolt SHA-256-ellenőrzőösszeggel érzékeli a bizonyítékok sérülését. Cache-hiba esetén a folyamat leáll, nincs automatikus frissítési vagy cache nélküli kerülőút.
- Regressziós tesztek a látható módosítást követő időbélyegre, a sérült cache-re, az adatminimalizált összefoglalóra és a hibaexportokra.

## 0.1.4, 8. csomagkiadás – Ellenőrzött technikai többlet

- Szűk, verziózott engedélylista a PDF utáni üres karakterekre és UUID MIME-záróhatárolóra.
- Érvényes aláírás mellett zöld eredmény és kék információs jelzés; az export megőrzi a részleges bájtlefedettséget és az eltérés adatait.
- Ismeretlen változások továbbra is figyelmeztetést kapnak; regressziós és integrációs tesztek.

## 0.1.4, 7. csomagkiadás – Okular fordítási kompatibilitás

- A felület összes QString-adatánál kifejezett szövegliterálokat használunk,
  így az Okular szigorú Qt-fordítási beállításaival is fordítható.
- A Qt6-regressziós teszt és a CI az automatikus ASCII/QString és
  QByteArray/QString átalakítások tiltásával fordul, hogy ezt a hibát jelezze.

## 0.1.4 – Időbélyegzés, nagy PDF-ek és korlátozott gyorsítótár

- A beágyazott, dokumentum- és archiválási időbélyegek önálló eredménye, szolgáltatója,
  időpontja és minősítése megjelenik. A megadott aláírási idő külön, nem hiteles időközlés.
- Az érvényes dokumentum-időbélyeg bekerül a jelenlegi PDF-lefedettség összesítésébe;
  a csak időbélyeget tartalmazó PDF-ek is ellenőrizhetők.
- Egyszerűbb alapnézet, változatlanul kinyitható JSON-részletek és exportálható DSS-jelentések.
- Folyamatos fájlolvasás, fájlalapú PDFBox-feldolgozás és megszakítható háttérmásolás,
  a DSS 32 bites eltolásai miatt legfeljebb 2 GiB − 1 bájtos bemenettel.
- A hálózati bizonyítékok közös gyorsítótára legfeljebb 256 MiB és 4096 adatfájl.
  A lejárt és a legrégebbi bejegyzések törlődnek; a zárolófájlok száma korlátos.
  Az OCSP-kulcs a teljes kéréshez kötött, a CRL/OCSP újrafelhasználása legfeljebb 5 perc.
- A README rögzíti a jelszóval védett PDF-ek támogatásának hiányát.

## Kiadásra előkészített korábbi módosítások

- A motor forráscsomagja tömörítetlen `engine-source.tar` lett. Ez megszünteti a zlib és
  zlib-ng eltérő gzip-kimenetéből adódó, azonos forrástartalom melletti ellenőrzőösszeg-eltérést.
  Csomagkészítési regressziós teszt ellenőrzi a megismételhetőséget és a forrásmódosítások felismerését.
- A `.gitignore` kizárja a `makepkg` által letöltött Okular-forrásarchívumot és kiadási
  aláírását is. A közreműködési útmutató tisztázza, hogy a `PKGBUILD` követett forrásfájl marad.
- Az összes saját útmutató és tájékoztató magyar nyelvű: a főoldali leírás, a változásnapló,
  az adatvédelmi, bizalmi, biztonsági, tesztelési és közreműködési dokumentáció,
  valamint a külső összetevők ismertetése és a hibajegysablon.
- A teljes útmutató a `README.md` fájlba került; a korábbi `README.hu.md` erre mutat.
- Magyar megnevezést kaptak az automatikus ellenőrzés saját munkafolyamat- és lépésfeliratai,
  valamint a bizalmi ujjlenyomatlista és a kizárási szabályok magyarázatai.
- Az eredeti licencszöveg és a műszaki azonosítók változatlanok.

## 0.1.3 – A hálózatkezelés, a lefedettség és a gyorsítótár megerősítése

- A TL/AIA/CRL/OCSP-lekérések csak nyilvános HTTP(S)-címeket érhetnek el.
  Kapcsolódáskor DNS-ellenőrzés történik; az átirányítások száma korlátozott,
  HTTPS-ről HTTP-re visszalépés és rendszerszintű proxy használata nincs.
- A válaszméretet a motor folyamatos olvasás és kitömörítés közben korlátozza;
  a túl nagy válaszokat és az átirányítások köztes választesteit megszakítja.
- A jelenlegi PDF-változat lefedettsége külön jelenik meg az aláírás érvényességétől.
  Az összesítés csak akkor zöld, ha minden aláírás érvényes, és legalább egy érvényes aláírás lefedi a jelenlegi változatot.
- Csak az aktuális dokumentumhoz illeszkedő mentett aláírói tanúsítványok töltődnek be.
  Újrafelhasználásuk legfeljebb 30 napig engedélyezett; sikeres íráskor a készlet legfeljebb
  256 tanúsítványra és 16 MiB-ra csökken, a lejárt és árva listakeresési segédadatok törlésével.
- Visszakerültek az első nyilvános feltöltésből kimaradt GitHub-ellenőrzések és forráskizárások.
- Hálózat nélkül futó, aláírt PDF-es, RFC3161- és CRL-integrációs tesztek, hálózatkezelési és
  gyorsítótár-regressziós tesztek, külön folyamatban indított motorteszt és képernyő nélküli
  Qt6-felületteszt került az automatikus ellenőrzésbe, Java 17 és 27 támogatással.
- A forráscsomag előkészítése automatikusan beépíti az ellenőrzőablak fejlécét az Okular javításába.

## 0.1.2 – Első nyilvános forráskiadás

- Magyar nyelvű EU-s aláírás-ellenőrző ablak a módosított Okular 26.08.2-höz.
- Helyi DSS 6.5.1-motor hitelesített EU-s és tagállami bizalmi listákkal.
- Külön érvényességi eredmény és aláírás-minősítés.
- Folyamatjelző, eltelt idő, megszakítás, színes eredményjelzés és kinyitható részletek.
- Eszköztárgomb és pontosított űrlap-, illetve aláírásértesítések.
- Tartós bizonyíték-gyorsítótár és tanúsítványhoz kötött bizalmilista-keresési segédadatok.
- A bizalmi listákat feldolgozó háttérszálak kifejezett leállítása.
- A mentett nyilvános aláírói tanúsítványok segédadatok maradnak, nem válnak feltétlen bizalmi ponttá.
- Nyilvános terjesztésre előkészített forrás semleges Java-névtérrel, felhasználói dokumentumok,
  validálási jelentések, géphez kötött személyes elérési utak és magánjellegű fejlesztési előzmények nélkül.
