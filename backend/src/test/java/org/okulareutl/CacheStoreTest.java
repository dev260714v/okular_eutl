// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.spi.client.http.DataLoader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class CacheStoreTest {
    @TempDir Path root;
    static final String URL="https://example.invalid/list.xml";
    static byte[] xml(Instant issue, Instant next) {
        return ("<TrustServiceStatusList xmlns='urn:test'><SchemeInformation><ListIssueDateTime>"+issue+"</ListIssueDateTime><NextUpdate><dateTime>"+next+"</dateTime></NextUpdate></SchemeInformation></TrustServiceStatusList>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static byte[] current() { return xml(Instant.now().minusSeconds(60),Instant.now().plusSeconds(600)); }
    static class Remote implements DataLoader {
        AtomicInteger gets=new AtomicInteger(),posts=new AtomicInteger();
        byte[] value=current(); boolean fail;
        public byte[] get(String url) { gets.incrementAndGet(); if(fail)throw new DSSException("unavailable");return value; }
        public DataAndUrl get(List<String> urls) {return new DataAndUrl(urls.get(0),get(urls.get(0)));}
        public byte[] post(String url,byte[] request) {posts.incrementAndGet();return request;}
        public void setContentType(String type) {}
    }
    Path cachedFile() { return root.resolve("lists/"+CacheStore.digest(URL.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".bin"); }
    @Test void nextProcessReusesFreshListWithoutNetwork() throws Exception {
        var remote=new Remote();new CacheStore(root).new Loader(remote,"lists",true).get(URL);
        var second=new CacheStore(root);assertArrayEquals(remote.value,second.new Loader(remote,"lists",true).get(URL));
        assertEquals(1,remote.gets.get());assertEquals(1,second.hits.get());
    }
    @Test void ageLimitForcesDownload() throws Exception {
        var remote=new Remote();var loader=new CacheStore(root).new Loader(remote,"lists",true);loader.get(URL);
        Files.setLastModifiedTime(cachedFile(),FileTime.from(Instant.now().minus(Duration.ofHours(7))));
        loader.get(URL);assertEquals(2,remote.gets.get());
    }
    @Test void signedNextUpdateOverridesRecentFilesystemTimestamp() throws Exception {
        var remote=new Remote();var loader=new CacheStore(root).new Loader(remote,"lists",true);loader.get(URL);
        Files.write(cachedFile(),xml(Instant.now().minusSeconds(100),Instant.now().minusSeconds(1)));
        loader.get(URL);assertEquals(2,remote.gets.get());
    }
    @Test void futureIssueDateIsNotReusable() {assertFalse(CacheStore.currentList(xml(Instant.now().plusSeconds(60),Instant.now().plusSeconds(600)),Instant.now()));}
    @Test void xmlExternalEntitiesAreRejected() {assertFalse(CacheStore.currentList("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>".getBytes(),Instant.now()));}
    @Test void corruptCacheTriggersRefresh() throws Exception {
        var remote=new Remote();var loader=new CacheStore(root).new Loader(remote,"lists",true);loader.get(URL);
        Files.writeString(cachedFile(),"corrupt");loader.get(URL);assertEquals(2,remote.gets.get());
    }
    @Test void staleCacheIsNotUsedWhenNetworkFails() throws Exception {
        var remote=new Remote();var loader=new CacheStore(root).new Loader(remote,"lists",true);loader.get(URL);
        Files.setLastModifiedTime(cachedFile(),FileTime.from(Instant.now().minus(Duration.ofHours(7))));remote.fail=true;
        assertThrows(DSSException.class,()->loader.get(URL));
    }
    @Test void failedDownloadDoesNotBecomeSuccessfulCacheEntry() throws Exception {
        var remote=new Remote();remote.fail=true;var loader=new CacheStore(root).new Loader(remote,"lists",true);
        assertThrows(DSSException.class,()->loader.get(URL));assertFalse(Files.exists(cachedFile()));
    }
    @Test void postResponsesAreNotCachedByUrl() throws Exception {
        var remote=new Remote();var loader=new CacheStore(root).new Loader(remote,"lists",true);
        assertArrayEquals(new byte[]{1},loader.post(URL,new byte[]{1}));assertArrayEquals(new byte[]{2},loader.post(URL,new byte[]{2}));assertEquals(2,remote.posts.get());
    }
    @Test void cachedCertificatesDoNotBecomeTrustAnchors() throws Exception {
        assertFalse(new CacheStore(root).signerCertificates(List.of()).getCertificateSourceType().isTrusted());
    }
    @Test void parallelConsumersReuseAtomicCacheEntry() throws Exception {
        var remote=new Remote();var a=new CacheStore(root).new Loader(remote,"lists",true);var b=new CacheStore(root).new Loader(remote,"lists",true);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {var futures=pool.invokeAll(List.of(()->a.get(URL),()->b.get(URL)));for(var f:futures)assertNotNull(f.get());assertEquals(1,remote.gets.get());}
        finally {pool.shutdownNow();}
    }

    @Test void storedCertificatesSurviveNextProcessButAreNotTrusted() throws Exception {
        var factory=java.security.cert.CertificateFactory.getInstance("X.509");
        try(var input=Files.newInputStream(Path.of("..","trust","lotl-anchors.pem"))) {
            var cert=(java.security.cert.X509Certificate)factory.generateCertificates(input).iterator().next();
            new CacheStore(root).rememberSigner(new eu.europa.esig.dss.model.x509.CertificateToken(cert));
            var reused=new CacheStore(root).signerCertificates(List.of(new eu.europa.esig.dss.model.x509.CertificateToken(cert)));
            assertEquals(1,reused.getNumberOfCertificates());assertFalse(reused.getCertificateSourceType().isTrusted());
        }
    }
    static class RecordingJob extends eu.europa.esig.dss.tsl.job.TLValidationJob {
        ExecutorService workers; boolean fail;
        @Override public void setExecutorService(ExecutorService service) { super.setExecutorService(service); workers=service; }
        @Override public synchronized void onlineRefresh() {
            try { assertEquals(42,workers.submit(()->42).get()); }
            catch (Exception e) { throw new IllegalStateException(e); }
            if(fail)throw new IllegalStateException("test failure");
        }
    }
    @Test void refreshTerminatesWorkersOnSuccess() throws Exception {
        var job=new RecordingJob();Main.refreshLists(job);assertTrue(job.workers.isShutdown());assertTrue(job.workers.awaitTermination(2,TimeUnit.SECONDS));
    }
    @Test void refreshTerminatesWorkersOnFailure() throws Exception {
        var job=new RecordingJob();job.fail=true;assertThrows(IllegalStateException.class,()->Main.refreshLists(job));assertTrue(job.workers.isShutdown());assertTrue(job.workers.awaitTermination(2,TimeUnit.SECONDS));
    }

    @Test void finalUkArchiveCanBeCachedWithoutBecomingCurrent() throws Exception {
        var remote=new Remote();remote.value=xml(Instant.now().minusSeconds(120),Instant.now().minusSeconds(60));
        var loader=new CacheStore(root).new Loader(remote,"lists",true);
        String archive="https://ec.europa.eu/tools/lotl/Final_EU_TSL-UKsigned.xml";
        loader.get(archive);loader.get(archive);assertEquals(1,remote.gets.get());
        assertFalse(CacheStore.currentList(remote.value,Instant.now()));
    }

    java.util.List<eu.europa.esig.dss.model.x509.CertificateToken> fixtureSigners() throws Exception {
        try(var in=Files.newInputStream(Path.of("..","trust","lotl-anchors.pem"))) {
            return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                .map(c->new eu.europa.esig.dss.model.x509.CertificateToken((java.security.cert.X509Certificate)c)).toList();
        }
    }
    @Test void listHintsAreBoundToExactCertificateNotSignerName() throws Exception {
        var signers=fixtureSigners();var store=new CacheStore(root);
        store.rememberLists(signers.get(0),Set.of("https://www.nmhh.hu/tl/pub/HU_TL.xml"));
        assertEquals(Set.of("https://www.nmhh.hu/tl/pub/HU_TL.xml"),new CacheStore(root).listsFor(List.of(signers.get(0))));
        assertTrue(store.listsFor(List.of(signers.get(1))).isEmpty());
        assertTrue(store.listsFor(List.of(signers.get(0),signers.get(1))).isEmpty());
    }
    @Test void expiredListHintsFallBackToAllLists() throws Exception {
        var signers=fixtureSigners();var store=new CacheStore(root);store.rememberLists(signers.get(0),Set.of("https://example.invalid/list"));
        try(var paths=Files.newDirectoryStream(root.resolve("validated-signers"),"*.lists.json")) {
            for(var path:paths)Files.setLastModifiedTime(path,FileTime.from(Instant.now().minus(Duration.ofHours(7))));
        }
        assertTrue(store.listsFor(List.of(signers.get(0))).isEmpty());
    }
    @Test void corruptListHintsFallBackToAllLists() throws Exception {
        var signers=fixtureSigners();var store=new CacheStore(root);store.rememberLists(signers.get(0),Set.of("https://example.invalid/list"));
        try(var paths=Files.newDirectoryStream(root.resolve("validated-signers"),"*.lists.json")) { for(var path:paths)Files.writeString(path,"bad-json"); }
        assertTrue(store.listsFor(List.of(signers.get(0))).isEmpty());
    }
    @Test void unrelatedCertificatesAreNotLoaded() throws Exception {
        var signers=fixtureSigners();var store=new CacheStore(root);
        for(var signer:signers)store.rememberSigner(signer);
        assertEquals(0,store.signerCertificates(List.of()).getNumberOfCertificates());
        assertEquals(1,store.signerCertificates(List.of(signers.get(0))).getNumberOfCertificates());
    }
    @Test void expiredSignerIsNotLoadedAndIsPrunedOnWrite() throws Exception {
        var signers=fixtureSigners();var store=new CacheStore(root);var old=signers.get(0);
        store.rememberSigner(old);store.rememberLists(old,Set.of(URL));
        Path cert=root.resolve("validated-signers/"+CacheStore.digest(old.getEncoded())+".cer");
        Files.setLastModifiedTime(cert,FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        assertEquals(0,store.signerCertificates(List.of(old)).getNumberOfCertificates());
        store.rememberSigner(signers.get(1));assertFalse(Files.exists(cert));
        assertFalse(Files.exists(cert.resolveSibling(cert.getFileName().toString().replace(".cer",".lists.json"))));
    }
    @Test void pruneEnforcesSignerCountLimitAndRemovesOrphanHints() throws Exception {
        Path dir=CacheStore.privateDirectory(root.resolve("validated-signers"));
        for(int i=0;i<CacheStore.MAX_SIGNERS+3;i++)Files.write(dir.resolve(i+".cer"),new byte[]{1});
        Files.writeString(dir.resolve("orphan.lists.json"),"[]");
        CacheStore.pruneSigners(dir);
        try(var paths=Files.newDirectoryStream(dir,"*.cer")) { int count=0;for(var p:paths)count++;assertEquals(CacheStore.MAX_SIGNERS,count); }
        assertFalse(Files.exists(dir.resolve("orphan.lists.json")));
    }
    @Test void pruneEnforcesTotalSignerByteLimit() throws Exception {
        Path dir=CacheStore.privateDirectory(root.resolve("validated-signers"));
        for(int i=0;i<18;i++)Files.write(dir.resolve(i+".cer"),new byte[(int)CacheStore.MAX_CERT_BYTES]);
        CacheStore.pruneSigners(dir);
        try(var paths=Files.newDirectoryStream(dir,"*.cer")) { long size=0;for(var p:paths)size+=Files.size(p);assertTrue(size<=CacheStore.MAX_SIGNER_BYTES); }
    }
    @Test void oversizedResponseCannotBeReturnedOrCached() throws Exception {
        var remote=new Remote();remote.value=new byte[(int)CacheStore.MAX_BYTES+1];
        assertThrows(DSSException.class,()->new CacheStore(root).new Loader(remote,"lists",true).get(URL));
        assertFalse(Files.exists(cachedFile()));
    }
    @Test void totalQuotaEvictsOldestAcrossAllBuckets() throws Exception {
        var store = new CacheStore(root);
        for (String bucket : List.of("trusted-lists", "issuer-certificates", "crl", "ocsp", "validated-signers")) {
            Path p = CacheStore.privateDirectory(root.resolve(bucket)).resolve("entry.bin");
            Files.write(p, new byte[20]);
            Files.setLastModifiedTime(p, FileTime.from(Instant.now().minusSeconds(bucket.equals("trusted-lists") ? 100 : 1)));
        }
        store.budget(() -> { store.prune(60, 3); return null; });
        try (var paths = Files.walk(root)) {
            var entries = paths.filter(p -> p.toString().endsWith(".bin")).toList();
            assertEquals(3, entries.size()); long bytes = 0; for (var p : entries) bytes += Files.size(p);
            assertTrue(bytes <= 60); assertFalse(Files.exists(root.resolve("trusted-lists/entry.bin")));
        }
    }
    @Test void startupRemovesExpiredResourcesWithoutRequestingTheirUrls() throws Exception {
        var store = new CacheStore(root);
        Path old = CacheStore.privateDirectory(root.resolve("crl")).resolve("old.bin");
        Files.write(old, new byte[10]); Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(301)));
        new CacheStore(root); assertFalse(Files.exists(old));
    }
    @Test void revocationPostCacheIsBoundToRequestAndExpires() throws Exception {
        var remote = new Remote(); var store = new CacheStore(root);
        var loader = store.new Loader(remote, "ocsp", Duration.ofMinutes(5), 1024);
        assertArrayEquals(new byte[]{1}, loader.post(URL, new byte[]{1}));
        assertArrayEquals(new byte[]{1}, loader.post(URL, new byte[]{1}));
        assertArrayEquals(new byte[]{2}, loader.post(URL, new byte[]{2}));
        assertEquals(2, remote.posts.get());
        try (var paths = Files.newDirectoryStream(root.resolve("ocsp"), "*.bin")) {
            for (Path p : paths) Files.setLastModifiedTime(p, FileTime.from(Instant.now().minusSeconds(301)));
        }
        loader.post(URL, new byte[]{1}); assertEquals(3, remote.posts.get());
    }
    @Test void lockFileCountDoesNotGrowWithUrlCount() throws Exception {
        var store = new CacheStore(root); var loader = store.new Loader(new Remote(), "trusted-lists", true);
        for (int i = 0; i < 100; i++) loader.get(URL + i);
        try (var paths = Files.list(root)) {
            assertTrue(paths.filter(p -> p.getFileName().toString().startsWith(".slot-")).count() <= 64);
        }
    }

    @Test void writingEnforcesProductionByteQuotaBeforeCreatingNewFile() throws Exception {
        var store = new CacheStore(root);
        Path directory = CacheStore.privateDirectory(root.resolve("issuer-certificates"));
        Path old = directory.resolve("old.bin");
        try (var file = new java.io.RandomAccessFile(old.toFile(), "rw")) { file.setLength(CacheStore.MAX_TOTAL_BYTES - 10); }
        Path next = directory.resolve("new.bin"); store.store(next, new byte[20]);
        assertFalse(Files.exists(old)); assertEquals(20, Files.size(next));
    }

}
