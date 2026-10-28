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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Cache of input evidence only. Never caches an aláírás-validity verdict or creates a trust anchor. */
final class CacheStore {
    static final Duration LIST_TTL = Duration.ofHours(6);
    static final Duration CERT_TTL = Duration.ofDays(30);
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final ConcurrentHashMap<String,Object> LOCKS = new ConcurrentHashMap<>();
    final Path root;
    final AtomicInteger hits = new AtomicInteger(), downloads = new AtomicInteger();
    CacheStore(Path root) throws IOException { this.root = privateDirectory(root); }
    static Path defaultPath() {
        String override = System.getProperty("okular.eutl.cacheDir");
        if (override != null && !override.isBlank()) return Path.of(override);
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && Path.of(xdg).isAbsolute() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("okular-eutl/v1");
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
    static boolean fresh(Path file, Duration ttl, boolean list, boolean archived) {
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            if (Files.isSymbolicLink(file) || age < 0 || age >= ttl.toMillis() || Files.size(file) > MAX_BYTES) return false;
            byte[] bytes = Files.readAllBytes(file);
            if (list) return listWindow(bytes, Instant.now(), archived);
            return !CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes)).isEmpty();
        } catch (Exception e) { return false; }
    }
    final class Loader implements DataLoader, DSSFileLoader {
        final DataLoader upstream;
        final Path directory;
        final boolean list;
        Loader(DataLoader upstream, String bucket, boolean list) throws IOException {
            this.upstream=upstream; this.directory=privateDirectory(root.resolve(bucket)); this.list=list;
        }
        public byte[] get(String url) {
            boolean archived = list && url.equals("https://ec.europa.eu/tools/lotl/Final_EU_TSL-UKsigned.xml");
            String key = digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path file = directory.resolve(key + ".bin");
            synchronized (LOCKS.computeIfAbsent(file.toString(), k -> new Object())) {
                try (var channel = FileChannel.open(directory.resolve(key+".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var lock = channel.lock()) {
                    if (fresh(file, list ? LIST_TTL : CERT_TTL, list, archived)) { hits.incrementAndGet(); return Files.readAllBytes(file); }
                    downloads.incrementAndGet();
                    byte[] bytes=upstream.get(url);
                    if (bytes == null || bytes.length==0) throw new DSSException("Üres hálózati válasz: " + url);
                    // Invalid evidence may still be returned to DSS for diagnosis, but is not reusable.
                    boolean cacheable = list ? listWindow(bytes, Instant.now(), archived) : isCertificate(bytes);
                    if (cacheable && bytes.length <= MAX_BYTES) atomicWrite(file, bytes);
                    return bytes;
                } catch (IOException e) { throw new DSSException("Gyorsítótár írási/olvasási hiba",e); }
            }
        }
        public DSSDocument getDocument(String url) { return new InMemoryDocument(get(url), url); }
        public DataAndUrl get(List<String> urls) {
            RuntimeException last = null;
            for (String url : urls) try { return new DataAndUrl(url,get(url)); } catch (RuntimeException e) { last=e; }
            if (last != null) throw last;
            return null;
        }
        public byte[] post(String url, byte[] content) { return upstream.post(url,content); }
        public void setContentType(String contentType) { upstream.setContentType(contentType); }
    }
    static boolean isCertificate(byte[] bytes) {
        try { return !CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes)).isEmpty(); }
        catch (Exception e) { return false; }
    }
    CommonCertificateSource signerCertificates() throws IOException {
        var source = new CommonCertificateSource();
        Path directory=privateDirectory(root.resolve("validated-signers"));
        try (var paths = Files.newDirectoryStream(directory,"*.cer")) {
            for (Path p : paths) {
                if (Files.isSymbolicLink(p) || Files.size(p)>1024*1024) continue;
                try {
                    byte[] bytes=Files.readAllBytes(p);
                    if (!p.getFileName().toString().equals(digest(bytes)+".cer")) continue;
                    var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
                    source.addCertificate(new CertificateToken(cert));
                } catch (Exception ignored) { /* malformed cache entries cannot establish trust */ }
            }
        }
        return source; // Adjunct certificates only, never CommonTrustedCertificateSource.
    }
    void rememberSigner(CertificateToken token) throws Exception {
        if (token == null) return;
        byte[] bytes=token.getEncoded();
        atomicWrite(privateDirectory(root.resolve("validated-signers")).resolve(digest(bytes)+".cer"),bytes);
    }

    Set<String> listsFor(List<CertificateToken> signers) throws IOException {
        if (signers.isEmpty()) return Set.of();
        Set<String> urls = new HashSet<>();
        for (var signer : signers) {
            if (signer == null) return Set.of();
            Path p = root.resolve("validated-signers").resolve(digest(signer.getEncoded())+".lists.json");
            try {
                long age=System.currentTimeMillis()-Files.getLastModifiedTime(p).toMillis();
                if (Files.isSymbolicLink(p) || age<0 || age>=LIST_TTL.toMillis() || Files.size(p)>65536) return Set.of();
                var node=Main.JSON.readTree(Files.readAllBytes(p));
                if (!node.isArray() || node.isEmpty()) return Set.of();
                for (var value:node) { if (!value.isTextual()) return Set.of();urls.add(value.asText()); }
            } catch (Exception e) { return Set.of(); }
        }
        return urls;
    }
    void rememberLists(CertificateToken signer, Set<String> urls) throws Exception {
        if (signer == null || urls.isEmpty()) return;
        Path p=privateDirectory(root.resolve("validated-signers")).resolve(digest(signer.getEncoded())+".lists.json");
        atomicWrite(p, Main.JSON.writeValueAsBytes(urls));
    }
}
