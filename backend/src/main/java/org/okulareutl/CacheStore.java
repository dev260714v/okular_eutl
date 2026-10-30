// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import eu.europa.esig.dss.model.*;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.client.http.*;
import eu.europa.esig.dss.spi.x509.CommonCertificateSource;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Cache of input evidence only. Never caches an aláírás-validity verdict or creates a trust anchor. */
final class CacheStore {
    static final Duration LIST_TTL = Duration.ofHours(6);
    static final Duration CERT_TTL = Duration.ofDays(30);
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_SIGNERS = 256;
    static final long MAX_SIGNER_BYTES = 16L * 1024 * 1024;
    static final long MAX_CERT_BYTES = 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    static final int MAX_ENTRIES = 4096;
    private static final Object[] SLOTS = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private static final Object BUDGET_LOCK = new Object();
    static final byte[] MAGIC = "EUTLCACHE2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final class CacheFailure extends DSSException {
        CacheFailure(Throwable cause) { super("Gyorsítótárhiba: az ellenőrzés leállt. A gyorsítótár javítása vagy törlése után próbálja újra.", cause); }
    }
    private final java.util.concurrent.atomic.AtomicReference<CacheFailure> failure = new java.util.concurrent.atomic.AtomicReference<>();
    CacheFailure broken(Throwable cause) {
        var error = cause instanceof CacheFailure c ? c : new CacheFailure(cause);
        failure.compareAndSet(null, error);
        return failure.get();
    }
    Path directory(String bucket) {
        checkHealthy();
        try { return privateDirectory(root.resolve(bucket)); }
        catch (IOException | RuntimeException e) { throw broken(e); }
    }
    void checkHealthy() { if (failure.get() != null) throw failure.get(); }
    final Path root;
    final AtomicInteger hits = new AtomicInteger(), downloads = new AtomicInteger();
    CacheStore(Path root) throws IOException {
        try { this.root = privateDirectory(root.toAbsolutePath().normalize()); }
        catch (IOException | RuntimeException e) { throw broken(e); }
        budget(() -> { prune(MAX_TOTAL_BYTES, MAX_ENTRIES); return null; });
    }

    @FunctionalInterface interface IOAction<T> { T run() throws IOException; }
    <T> T budget(IOAction<T> action) throws IOException {
        checkHealthy();
        synchronized (BUDGET_LOCK) {
            try (var channel = FileChannel.open(root.resolve(".budget.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) { return action.run(); }
            catch (IOException | RuntimeException e) { throw broken(e); }
        }
    }

    // Walk without following links. Expired data is removed even when its URL is never requested again.
    void prune(long maxBytes, int maxEntries) throws IOException {
        var entries = new ArrayList<Path>();
        long now = System.currentTimeMillis(), total = 0;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.toList()) {
                if (p.equals(root) || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = p.getFileName().toString();
                if (p.getParent().equals(root) && (name.equals(".budget.lock") || name.startsWith(".slot-"))) continue;
                var attributes = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                long age = now - attributes.lastModifiedTime().toMillis();
                String bucket = root.relativize(p).getName(0).toString();
                Duration ttl = bucket.equals("trusted-lists") || name.endsWith(".lists.bin") ? LIST_TTL
                    : bucket.equals("crl") || bucket.equals("ocsp") ? Duration.ofMinutes(5) : CERT_TTL;
                if (!attributes.isRegularFile()) throw broken(new IOException("Nem szabályos gyorsítótár-bejegyzés"));
                if (age < 0 || age >= ttl.toMillis() || name.endsWith(".tmp") || name.endsWith(".lock")) {
                    Files.deleteIfExists(p);
                } else { entries.add(p); total += attributes.size(); }
            }
        }
        entries.sort(Comparator.comparingLong(p -> {
            try { return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis(); }
            catch (IOException e) { return Long.MIN_VALUE; }
        }));
        int count = entries.size();
        for (Path p : entries) {
            if (total <= maxBytes && count <= maxEntries) break;
            total -= Files.size(p); count--; Files.deleteIfExists(p);
        }
    }

    void store(Path target, byte[] bytes) throws IOException {
        budget(() -> {
            if (bytes.length > MAX_TOTAL_BYTES) return null;
            Files.deleteIfExists(target);
            prune(MAX_TOTAL_BYTES - bytes.length, MAX_ENTRIES - 1);
            atomicWrite(target, bytes);
            return null;
        });
    }
    static Path defaultPath() {
        String override = System.getProperty("okular.eutl.cacheDir");
        if (override != null && !override.isBlank()) return Path.of(override);
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && Path.of(xdg).isAbsolute() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("okular-eutl/v2");
    }
    static Path privateDirectory(Path p) throws IOException {
        Files.createDirectories(p);
        if (Files.isSymbolicLink(p)) throw new IOException("A gyorsítótár nem lehet szimbolikus hivatkozás: " + p);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".write-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    /** Check the signed validity interval before reusing XML; DSS still verifies its signature on every run. */
    static boolean currentList(byte[] bytes, Instant now) { return listWindow(bytes, now, false); }
    static boolean listWindow(byte[] bytes, Instant now, boolean archived) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            var infos = doc.getDocumentElement().getElementsByTagNameNS("*", "SchemeInformation");
            if (infos.getLength() != 1) return false;
            var info = (Element) infos.item(0);
            var issues = info.getElementsByTagNameNS("*", "ListIssueDateTime");
            var updates = info.getElementsByTagNameNS("*", "NextUpdate");
            if (issues.getLength() != 1) return false;
            Instant issue = OffsetDateTime.parse(issues.item(0).getTextContent().strip()).toInstant();
            // The Commission's final UK list is an immutable archive. Cache its bytes,
            // but Main.usable still excludes it as a current trust source.
            if (archived) return !issue.isAfter(now);
            if (updates.getLength() != 1) return false;
            Instant next = OffsetDateTime.parse(updates.item(0).getTextContent().strip()).toInstant();
            return !issue.isAfter(now) && next.isAfter(now);
        } catch (Exception e) { return false; }
    }
    // One atomic file binds payload bytes to a checksum. This detects accidental
    // corruption; it never replaces DSS signature/validity checks or grants trust.
    static byte[] pack(byte[] bytes) {
        var data = new byte[MAGIC.length + 32 + bytes.length];
        System.arraycopy(MAGIC, 0, data, 0, MAGIC.length);
        var checksum = HexFormat.of().parseHex(digest(bytes));
        System.arraycopy(checksum, 0, data, MAGIC.length, 32);
        System.arraycopy(bytes, 0, data, MAGIC.length + 32, bytes.length);
        return data;
    }
    static byte[] unpack(byte[] data) throws IOException {
        if (data.length < MAGIC.length + 32 || !Arrays.equals(MAGIC, Arrays.copyOf(data, MAGIC.length)))
            throw new IOException("Sérült gyorsítótár-formátum");
        byte[] bytes = Arrays.copyOfRange(data, MAGIC.length + 32, data.length);
        if (!MessageDigest.isEqual(Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + 32),
                HexFormat.of().parseHex(digest(bytes)))) throw new IOException("Sérült gyorsítótár-ellenőrzőösszeg");
        return bytes;
    }
    byte[] cached(Path file, Duration ttl, long maxBytes, boolean list, boolean certificate, boolean archived) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
            throw new IOException("Nem szabályos gyorsítótár-bejegyzés");
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
        if (age < 0 || age >= ttl.toMillis()) return null;
        if (Files.size(file) > maxBytes + MAGIC.length + 32) throw new IOException("Túlméretes gyorsítótár-bejegyzés");
        byte[] bytes = unpack(Files.readAllBytes(file));
        if (certificate && !isCertificate(bytes)) throw new IOException("Sérült gyorsítótár-tanúsítvány");
        if (list && !listWindow(bytes, Instant.now(), archived)) return null; // normal signed-list expiry
        return bytes;
    }
    final class Loader implements DataLoader, DSSFileLoader {
        final DataLoader upstream;
        final Path directory;
        final boolean list, certificate;
        final Duration ttl;
        final long maxBytes;
        Loader(DataLoader upstream, String bucket, boolean list) throws IOException {
            this(upstream, bucket, list, !list, list ? LIST_TTL : CERT_TTL, list ? MAX_BYTES : MAX_CERT_BYTES);
        }
        // Revocation responses are cached as evidence, keyed by the complete request.
        // DSS still checks their signatures, thisUpdate/nextUpdate and applicability.
        Loader(DataLoader upstream, String bucket, Duration ttl, long maxBytes) throws IOException {
            this(upstream, bucket, false, false, ttl, maxBytes);
        }
        private Loader(DataLoader upstream, String bucket, boolean list, boolean certificate, Duration ttl, long maxBytes) throws IOException {
            this.upstream = upstream;
            this.directory = directory(bucket);
            this.list = list; this.certificate = certificate; this.ttl = ttl; this.maxBytes = maxBytes;
        }
        public byte[] get(String url) { return fetch(url, null); }
        private byte[] fetch(String url, byte[] request) {
            checkHealthy();
            boolean archived = list && url.equals("https://ec.europa.eu/tools/lotl/Final_EU_TSL-UKsigned.xml");
            String identity = request == null ? url : url + "\0POST\0" + digest(request);
            String key = digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path file = directory.resolve(key + ".bin");
            int slot = (file.toString().hashCode() & Integer.MAX_VALUE) % SLOTS.length;
            synchronized (SLOTS[slot]) {
                try (var channel = FileChannel.open(root.resolve(".slot-" + slot), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var lock = channel.lock()) {
                    byte[] cached = budget(() -> cached(file, ttl, maxBytes, list, certificate, archived));
                    if (cached != null) { hits.incrementAndGet(); return cached; }
                    downloads.incrementAndGet();
                    byte[] bytes = request == null ? upstream.get(url) : upstream.post(url, request);
                    if (bytes == null || bytes.length == 0) throw new DSSException("Üres hálózati válasz: " + url);
                    if (bytes.length > maxBytes) throw new DSSException("A hálózati válasz túllépte a méretkorlátot");
                    boolean cacheable = list ? listWindow(bytes, Instant.now(), archived) : !certificate || isCertificate(bytes);
                    if (cacheable) store(file, pack(bytes));
                    return bytes;
                } catch (IOException e) { throw broken(e); }
            }
        }
        public DSSDocument getDocument(String url) { return new InMemoryDocument(get(url), url); }
        public DataAndUrl get(List<String> urls) {
            RuntimeException last = null;
            for (String url : urls) try { return new DataAndUrl(url, get(url)); } catch (RuntimeException e) { last = e; }
            if (last != null) throw last;
            return null;
        }
        public byte[] post(String url, byte[] content) {
            return list || certificate ? upstream.post(url, content) : fetch(url, content);
        }
        public void setContentType(String contentType) { upstream.setContentType(contentType); }
    }
    static boolean isCertificate(byte[] bytes) {
        try { return !CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes)).isEmpty(); }
        catch (Exception e) { return false; }
    }
    CommonCertificateSource signerCertificates(List<CertificateToken> signers) throws IOException {
        var source = new CommonCertificateSource();
        Path directory=directory("validated-signers");
        // Exact lookups only: opening one document must not parse every past signer's certificate.
        for (var signer : signers) {
            if (signer == null) continue;
            Path p = directory.resolve(digest(signer.getEncoded()) + ".cer");
            try {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis();
                if (Files.isSymbolicLink(p) || Files.size(p)>MAX_CERT_BYTES) throw new IOException("Sérült aláírói tanúsítvány");
                if (age < 0 || age >= CERT_TTL.toMillis()) continue;
                try {
                    byte[] bytes=Files.readAllBytes(p);
                    if (!p.getFileName().toString().equals(digest(bytes)+".cer")) throw new IOException("Sérült aláírói tanúsítvány");
                    var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
                    source.addCertificate(new CertificateToken(cert));
                } catch (Exception e) { throw broken(e); }
            } catch (NoSuchFileException ignored) { /* cache miss */ }
            catch (IOException e) { throw broken(e); }
        }
        return source; // Adjunct certificates only, never CommonTrustedCertificateSource.
    }
    void rememberSigner(CertificateToken token) throws Exception {
        if (token == null) return;
        byte[] bytes=token.getEncoded();
        if (bytes.length > MAX_CERT_BYTES) return;
        Path directory = directory("validated-signers");
        store(directory.resolve(digest(bytes) + ".cer"), bytes);
        budget(() -> { pruneSigners(directory); return null; });
    }

    static void pruneSigners(Path directory) throws IOException {
        var entries = new ArrayList<Path>();
        long now = System.currentTimeMillis(), total = 0;
        try (var paths = Files.newDirectoryStream(directory, "*.cer")) {
            for (Path p : paths) {
                long age = now - Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (Files.isSymbolicLink(p) || age < 0 || age >= CERT_TTL.toMillis() || Files.size(p) > MAX_CERT_BYTES) {
                    removeSigner(p);
                } else { entries.add(p); total += Files.size(p); }
            }
        }
        entries.sort(Comparator.comparingLong(p -> {
            try { return Files.getLastModifiedTime(p).toMillis(); }
            catch (IOException e) { return Long.MIN_VALUE; }
        }));
        int count = entries.size();
        for (Path p : entries) {
            if (count <= MAX_SIGNERS && total <= MAX_SIGNER_BYTES) break;
            total -= Files.size(p); count--; removeSigner(p);
        }
        // Hints without a live certificate cannot grow indefinitely either.
        try (var paths = Files.newDirectoryStream(directory, "*.lists.bin")) {
            for (Path p : paths) {
                Path cert = directory.resolve(p.getFileName().toString().replace(".lists.bin", ".cer"));
                if (Files.isSymbolicLink(p) || !Files.exists(cert, LinkOption.NOFOLLOW_LINKS)
                        || now - Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() >= LIST_TTL.toMillis())
                    Files.deleteIfExists(p);
            }
        }
    }

    static void removeSigner(Path p) throws IOException {
        Files.deleteIfExists(p);
        Files.deleteIfExists(p.resolveSibling(p.getFileName().toString().replace(".cer", ".lists.bin")));
    }

    Set<String> listsFor(List<CertificateToken> signers) throws IOException {
        checkHealthy();
        if (signers.isEmpty()) return Set.of();
        Set<String> urls = new HashSet<>();
        for (var signer : signers) {
            if (signer == null) return Set.of();
            Path p = root.resolve("validated-signers").resolve(digest(signer.getEncoded())+".lists.bin");
            try {
                long age=System.currentTimeMillis()-Files.getLastModifiedTime(p).toMillis();
                if (Files.isSymbolicLink(p) || Files.size(p)>65536) throw new IOException("Sérült listakeresési bejegyzés");
                if (age<0 || age>=LIST_TTL.toMillis()) return Set.of();
                var node=Main.JSON.readTree(unpack(Files.readAllBytes(p)));
                if (!node.isArray() || node.isEmpty()) throw new IOException("Sérült listakeresési segédadat");
                for (var value:node) { if (!value.isTextual()) throw new IOException("Sérült listakeresési cím");urls.add(value.asText()); }
            } catch (NoSuchFileException e) { return Set.of(); }
            catch (Exception e) { throw broken(e); }
        }
        return urls;
    }
    void rememberLists(CertificateToken signer, Set<String> urls) throws Exception {
        if (signer == null || urls.isEmpty()) return;
        Path p=directory("validated-signers").resolve(digest(signer.getEncoded())+".lists.bin");
        store(p, pack(Main.JSON.writeValueAsBytes(urls)));
    }
}
