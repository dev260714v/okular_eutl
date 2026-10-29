# Bizalmi beállítások

A DSS a `trust/lotl-anchors.pem` fájlban található nyilvános LOTL-aláíró tanúsítványokból indul ki.
A megengedett SHA-256-ujjlenyomatokat a `trust/allowed-sha256.txt` rögzíti,
egyezésüket a `tests/verify-trust.py` ellenőrzi.

A hivatalos közzététel címét a `trust/oj-url.txt` tartalmazza:
[az Európai Unió Hivatalos Lapjának C/2026/1944. számú közleménye](https://eur-lex.europa.eu/eli/C/2026/1944/oj).
A mellékelt tanúsítványokat a fejlesztés során összevetettük a közzétett ujjlenyomatokkal.
Ezek nem felhasználók, önkormányzati szervezetek vagy valódi üzemi tesztaláírók tanúsítványai.

## A bizalmi listák hitelesítése

A motor ezekből a bizalmi pontokból hitelesíti az aktuális EU-s LOTL-t, vagyis a tagállami
bizalmi listák jegyzékét. A DSS követi a LOTL aláírókulcsainak átvezetését szolgáló
mechanizmust, és hitelesíti a hivatkozott tagállami bizalmi listákat.
Egy lista csak sikeres letöltés, érvényes aláírás és aktuális kiadási/következő frissítési
időszak esetén szinkronizálható. A DSS `DESYNCHRONIZED` és `SYNCHRONIZED`
gyorsítótár-állapota eltérő feldolgozási szakaszban jelzi a sikeresen letöltött adatot;
egyik sem kerüli meg ezeket a biztonsági ellenőrzéseket.

Az Egyesült Királyság utolsó, archivált listájának bájtjai tárolhatók a gyorsítótárban,
de lejárt vagy nem aktuális lista nem válik aktuális bizalmi forrássá.
Az ismert aláírók keresési segédadatai csak a hitelesített LOTL-ben már szereplő címek
közül választanak; nem hoznak létre új, önálló bizalmi pontokat.
Ha a kiválasztott listák alapján nem születik egyértelmű eredmény, a motor teljes listakereséssel próbálkozik újra.

## Tanúsítványok és karbantartás

A kibocsátói és a korábban érvényes aláírói tanúsítványok segédbizonyítékok.
A visszavonási állapotot, az érvényességi időszakokat és az EU-s minősítést a DSS
minden új dokumentumnál értékeli. A gyorsítótárazott tanúsítványok soha nem kerülnek
át a bizalmi források gyűjteményébe.

A bizalmi beállításokat karban kell tartani. A LOTL hivatalos aláírói adatainak változásakor
előbb ellenőrizd az új hivatalos közzétételt és ujjlenyomatokat, és csak ezután módosítsd
ezeket a fájlokat, majd generáld újra a csomag ellenőrzőösszegeit.
Egy dokumentum aláírói tanúsítványát soha ne másold a LOTL-bizalmi fájlba pusztán azért,
hogy az ellenőrzés sikeres legyen.
