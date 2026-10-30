# Közreműködési útmutató

Mesterségesen előállított dokumentumokkal és személyes adat nélküli reprodukálási lépésekkel dolgozz.
Valódi PDF-et, aláírói tanúsítványdumpot, magánkulcsot, validálási jelentést vagy személyes gépi elérési utat ne adj a tárolóhoz.
A projekt saját dokumentációját, hibajegysablonjait és változásnaplóját magyarul tartsd karban.
A fájlnevek, parancsok, API-azonosítók, komponensnevek és eredeti licencszövegek megőrzik az eredeti alakjukat.

## Fordításkor keletkező fájlok

A `makepkg` által letöltött `okular-*.tar.xz` forrásarchívumot és a hozzá tartozó
`okular-*.tar.xz.sig` aláírást a `.gitignore` kizárja. A generált motorarchívum,
a lefordított csomagok és a fordítási könyvtárak sem kerülnek a tárolóba.

A motor forráscsomagja `engine-source.tar`, tömörítés nélkül. Így a helyi zlib vagy
zlib-ng eltérő tömörítési eredménye nem változtatja meg a csomag ellenőrzőösszegét.
A korábban létrehozott `engine-source.tar.gz` figyelmen kívül hagyható vagy törölhető;
az új csomagleírás már nem használja. A megismételhetőséget a
`python3 tests/test-packaging.py` parancs ellenőrzi.

A `PKGBUILD` viszont követett forrásfájl: a verziókat, függőségeket és ellenőrzőösszegeket
is tartalmazza, ezért nem zárjuk ki. A `.gitignore` egy már követett fájl módosításait
egyébként sem rejtené el. A `scripts/prepare-source.py` a generált forráscsomag alapján
frissíti az ellenőrzőösszegeket; ezek változását feltöltés előtt vizsgáld meg:

```sh
git diff -- PKGBUILD
```

Módosítatlan forrás és azonos csomagkészítési környezet esetén az előkészítés nem okozhat
tartalmi eltérést a már megfelelő ellenőrzőösszegeket tartalmazó `PKGBUILD` fájlban.
Forrásmódosítás vagy eltérő helyi csomagtartalom esetén az eltérés
okát ellenőrizni kell; a csomagleírás változásait ne rejtsd el helyi Git-beállítással.

## A módosítások ellenőrzése

1. Futtasd a `python3 scripts/audit-public.py` parancsot, és nézz át minden véglegesítésre előkészített fájlt.
2. Futtasd a `python3 tests/verify-trust.py` parancsot.
3. JDK 17 vagy újabb környezetben futtasd: `mvn -B -ntp -f backend/pom.xml verify`.
4. Ellenőrizd a lefordított motort külön folyamatban is:
   `python3 tests/engine-cli.py backend/target/okular-eutl-0.1.4.jar`.
5. A motor, a tesztek vagy a bizalmi adatok módosítása után futtasd a `python3 scripts/prepare-source.py` parancsot.
6. Ez a szkript az elsődleges `eutldialog.h` fejlécet is beépíti az `okular-eutl.patch` fájlba.
   A generált javítást és a PKGBUILD ellenőrzőösszegeit is véglegesítsd.
   Ellenőrizd, hogy a javítás továbbra is alkalmazható a pontosan támogatott eredeti Okular-forrásra.

A bizalmi listák elfogadását, a visszavonási adatok frissességét, a fájllenyomatokat vagy
az összesített eredményt érintő módosításokhoz regressziós teszt szükséges.
Hiányzó bizonyítékot, nem megbízható tanúsítványt vagy ismeretlen eredményt soha ne jeleníts meg
sikeres ellenőrzésként. A gyorsítótárban tárolt aláírót ne emeld bizalmi ponttá.

A kizárólag felületi teszt a Qt6 fejlesztői csomagjaival fordítható:

```sh
c++ -std=c++17 -fPIC -DQT_USE_QSTRINGBUILDER -DQT_USE_FAST_OPERATOR_PLUS -DQT_NO_CAST_FROM_ASCII -DQT_NO_CAST_TO_ASCII -DQT_NO_CAST_FROM_BYTEARRAY -I. tests/ui-smoke.cpp $(pkg-config --cflags --libs Qt6Widgets) -o /tmp/okular-eutl-ui-test
QT_QPA_PLATFORM=offscreen /tmp/okular-eutl-ui-test
```

Az automatikus ellenőrzés a Java-teszteket JDK 17 és 27 alatt, a Qt6-tesztet képernyő nélkül futtatja.
A Java-integrációs tesztek futás közben generálják az összes tesztkulcsot, tanúsítványt,
CRL-t, RFC3161-időbélyeget és aláírt PDF-et. A mesterséges teszthitelesítő nem válik az alkalmazás bizalmi pontjává.
A hálózati tesztek saját DNS- és címengedélyezési szabályt adnak a helyi HTTP-tesztkiszolgálónak;
ez a tesztbeállítás futtatási konfigurációval nem kapcsolható be.

A CI a felületet normál QString-módban és a KDE-féle `QT_USE_QSTRINGBUILDER` /
`QT_USE_FAST_OPERATOR_PLUS` beállításokkal is lefordítja és futtatja.

A Java-tesztek és a képernyő nélküli párbeszédablak-tesztek nem helyettesítik a teljes,
javított Okular lefordítását és kézi asztali kipróbálását.
A módosítási kérelemben azt sorold fel, amit ténylegesen ellenőriztél.
