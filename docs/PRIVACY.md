# Adatvédelem és adatáramlás

Az Okular ellenőrzőablaka a lemezre mentett PDF-ről bájtpontos másolatot készít egy
elkülönített ideiglenes könyvtárba, majd ellenőrzi, hogy a kapott eredmény SHA-256-lenyomata
egyezik-e ezzel a példánnyal. A motor nem tölti fel a PDF-et ellenőrzési szolgáltatáshoz.
Az ideiglenes könyvtár az ellenőrzőablak megszűnésekor törlődik.
Ez szokásos fájltörlés, nem garantált biztonságos adattörlés.

## Hálózati kérések

A kimenő kérések EU-s és tagállami bizalmi listákat, AIA-kibocsátói tanúsítványokat,
valamint OCSP/CRL-bizonyítékokat kérnek le. Az OCSP-kérések tanúsítványazonosítókat
fedhetnek fel a válaszadó előtt, a hálózati szolgáltatók pedig a kérő IP-címét is láthatják.
A hálózati elérés nem anonim.

A motor csak nyilvános HTTP(S)-célokat engedélyez, és a DNS-válaszokat kapcsolódáskor,
az átirányított kapcsolatoknál is ellenőrzi. Rendszerszintű proxyt nem használ.
Ez korlátozza a helyi hálózat elérését, de a külső bizonyítékkiszolgálókat nem teszi
anonimmá vagy önmagukban megbízhatóvá.

## Tartós gyorsítótár

A felhasználói gyorsítótár nyilvános ellenőrzési bizonyítékokat és újra felhasználható,
nyilvános aláírói tanúsítványokat tárol. A tanúsítványok neveket és szervezeti adatokat is
tartalmazhatnak, bár magánkulcsot nem tartalmaznak. A gyorsítótár könyvtárai csak az adott
felhasználó számára hozzáférhetők. Az alkalmazás nem menti ide a PDF-et vagy az ellenőrzés
zöld/piros eredményét.

Az aláírói tanúsítványok közül csak az aktuális dokumentumhoz pontosan illeszkedő,
30 napnál nem régebbi bejegyzések használhatók újra. Sikeres írás után a tárolt készlet
legfeljebb 256 tanúsítvány és 16 MiB. A motor ekkor törli a lejárt és legrégebbi bejegyzéseket,
valamint az árván maradt listakeresési segédadatokat is.
Azonnali eltávolításhoz kézzel töröld a bizonyítékok gyorsítótárát;
az ellenőrzőablak bezárása önmagában nem törli ezt a tartós tárat.
Az alapértelmezett elérési utat a [fő útmutató](../README.md#gyorsítótár-és-natív-tanúsítványtárolás) ismerteti.

## Jelentések és naplók

A mentett jelentések és naplók tartalmazhatnak aláírói személyazonosító adatokat,
tanúsítványadatokat, dokumentumlenyomatokat, fájlelérési utakat, ellenőrzési időpontokat
és szolgáltatási címeket. Megosztás előtt nézd át és anonimizáld őket.
Az ablak bezárása nem törli azokat a jelentéseket, amelyeket a felhasználó külön elmentett.

## Nyilvános forráskód

A forrástárolóból kizárjuk a magándokumentumokat, a valódi üzemi tesztadatokat, a validálási
jelentéseket, a géphez kötött személyes elérési utakat, a hitelesítési adatokat és a generált
fejlesztői binárisokat. Csak mesterséges példák és nyilvános EU-s bizalmi tanúsítványok szerepelnek benne.
Új fájl véglegesítése előtt futtasd a `scripts/audit-public.py` szkriptet, és kézzel is vizsgáld át a tartalmat.

A közös gyorsítótár legfeljebb 256 MiB és 4096 adatfájl; induláskor és minden
írás előtt törli a lejárt, szükség esetén a legrégebbi adatokat. Az OCSP/CRL
válaszok legfeljebb öt percig használhatók újra, további DSS-validálás mellett.
Az OCSP-válasz kulcsa a célcímhez és a teljes kéréshez kötött. A zároláshoz
legfeljebb 65 állandó fájl szükséges, nem hozunk létre URL-enként új zárfájlt.
