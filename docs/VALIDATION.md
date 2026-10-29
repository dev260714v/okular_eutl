# Az ellenőrzések és tesztek állapota

A forrásellenőrzési munkafolyamat JDK 17 és 27 alatt futtatja a Maven ellenőrzéseit,
vizsgálja a nyilvános forrást és a rögzített bizalmi tanúsítvány-ujjlenyomatokat,
valamint ellenőrzi a megismételhető csomagkészítést és az elsődleges felületi fejléc
szinkronját a telepítendő Okular-javítással.
Külön automatikus feladat fordítja és képernyő nélkül futtatja a Qt6-párbeszédablak tesztjét.
A függőségekkel egyesített JAR külön folyamatként is elindul generált, aláírás nélküli és
hibás PDF-ekkel: a teszt ellenőrzi a kilépési kódot, a JSON-állapotot és a bemeneti fájl lenyomatát.

## Java-egységtesztek

A tesztek lefedik a bizalmi listák elfogadását; a gyorsítótár lejáratát, sérülését és párhuzamos
használatát; a pontos tanúsítványhoz kötött keresési segédadatokat; a korlátozott,
bizalmi pontot nem létrehozó tanúsítvány-újrafelhasználást; és a háttérszálak leállítását.
Ellenőrzik továbbá a nyilvános hálózati címek kapcsolódáskori szűrését, az átirányítási szabályokat
és a HTTP-válaszok méretkorlátját, beleértve a darabolt átvitelt, az OCSP POST-válaszát és a kitömörített gzip-adatot.

## Aláírt PDF-ek integrációs tesztjei

A hálózat nélkül futó tesztek valódi PDF-aláírásokat, mesterséges hitelesítőt és aláírót,
aláírt CRL-eket és RFC3161-időbélyegeket hoznak létre a memóriában. Az alábbi eseteket vizsgálják:

- Érvényes aláírás és a jelenlegi PDF-változat pontos lefedettsége.
- Aláírt bájtok módosítása esetén `TOTAL_FAILED / HASH_FAILURE` eredmény, mentett aláíró mellett is.
- Visszavont aláíró sikertelen ellenőrzése.
- Hiányzó megbízható tanúsítványlánc esetén nem eldönthető eredmény, mentett aláíró mellett is.
- Későbbi, aláírás nélküli PDF-változat részleges lefedettségként történő jelzése.
- Ugyanattól a mentett aláírótól származó második PDF külön dokumentumellenőrzése.
- Valódi RFC3161-időbélyeg ellenőrzése a PDF-aláírással együtt.

A mesterséges hitelesítőt kizárólag a tesztek adják át az ellenőrzőnek;
soha nem kerül az alkalmazás bizalmi tanúsítványcsomagjába.
A mesterséges magánkulcsok és aláírt dokumentumok teszteléskor keletkeznek,
nem terjesztjük őket előre elkészített tesztfájlokként.
A PDF-es tesztek a motor jelentés-összeállító kódját használják,
de nem szimulálják a teljes EU-s LOTL és a tagállami listák közzétételi, illetve aláírási infrastruktúráját.

## Felületi tesztek

A Qt6-teszt vizsgálja a folyamatjelzőt, az érvényes, érvénytelen, nem eldönthető és vegyes eredményeket,
az aláírás nélküli bemenetet, a hibás JSON-t, az eltérő fájllenyomatot, a megszakítást,
valamint a hiányzó, részleges és ismeretlen jelenlegi PDF-lefedettséget.
A segédfolyamata mesterséges; a tényleges PDF-aláírások kriptográfiai vizsgálatát a Java-tesztek végzik.

## A vizsgálatok korlátai

Ezek az ellenőrzések nem helyettesítik a teljes javított Okular lefordítását és kézi kipróbálását
a céldisztribúcióban, és nem tanúsítják minden PDF megfelelő kezelését.
Az EU-s bizalmi beállításokat, a visszavonási adatok frissességét, a korábbi időpontra
vonatkozó ellenőrzést és a minősített aláírási státusz értelmezését karban kell tartani és felül kell vizsgálni.
Hiányzó bizonyítékból megalapozás nélkül nem válhat sikeres vagy érvénytelen eredmény.

## Időbélyeg- és nagyfájl-regressziók

A mesterséges RFC 3161-időbélyeges tesztek ellenőrzik a beágyazott időbélyeg
külön eredményét, a hiányzó időbélyeg jelzését, az önálló dokumentum-időbélyeget,
a hibás és nem megbízható időbélyeg elutasítását, valamint a későbbi aláíratlan
változat lefedetlenségét. Egy 270 MiB-nál nagyobb, futáskor generált PDF valódi
aláírása és validálása is része az integrációs tesztnek.

A motor CLI-próbája ritka (sparse), érvényes PDF-fel vizsgálja a régi 256 MiB-os
határ átlépését, a 2 GiB − 1 bájtos bemenet feldolgozását 768 MiB heap mellett,
és a 2 GiB-os, illetve 2 GiB + 1 bájtos bemenet elutasítását. Ezek nem állítják, hogy minden
azonos méretű vagy összetettségű PDF ugyanannyi memóriát igényel.

A gyorsítótár tesztjei több tároló közös kvótáját, az induláskori lejárattörlést,
a kéréshez kötött OCSP-tárolást és a zárolófájlok korlátját is vizsgálják.
A Qt-próba külön ellenőrzi az érvényes, hibás és nem eldönthető időbélyeg kijelzését,
a csak időbélyeget tartalmazó dokumentumot, valamint a 256 MiB feletti munkapéldányt.
