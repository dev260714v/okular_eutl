// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.job.DocumentInfo;
import eu.europa.esig.dss.model.tsl.*;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.spi.client.http.Protocol;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.tsl.function.OfficialJournalSchemeInformationURI;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.job.sync.SynchronizationStrategy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.Instant;
import java.util.*;

public final class Main {
    static final ObjectMapper JSON = new ObjectMapper().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String LOTL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml";

    // Reject failed/expired lists before they can add trusted certificates.
    static boolean usable(DocumentInfo info) {
        if (info == null || info.getValidationCacheInfo() == null
                || !info.getValidationCacheInfo().isValid()
                || info.getDownloadCacheInfo() == null
                || !("SYNCHRONIZED".equals(info.getDownloadCacheInfo().getStatusName())
                     || "DESYNCHRONIZED".equals(info.getDownloadCacheInfo().getStatusName()))
                || !(info.getParsingCacheInfo() instanceof TLParsingInfoRecord parsing)) return false;
        Date now = new Date();
        return parsing.getIssueDate() != null && !parsing.getIssueDate().after(now)
            && parsing.getNextUpdateDate() != null && parsing.getNextUpdateDate().after(now);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Használat: okular-eutl-engine input.pdf output-directory trust-directory");
            System.exit(2);
        }
        Path input = Path.of(args[0]), output = Path.of(args[1]), config = Path.of(args[2]);
        if (!Files.isDirectory(output)) throw new IllegalArgumentException("Hiányzó eredménykönyvtár");
        long startedNanos = System.nanoTime();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schema", 1);
        result.put("engine", "DSS 6.5.1 / Okular EUTL 0.1.2");
        result.put("validationTime", Instant.now().toString());
        result.put("status", "ERROR");
        result.put("signatures", List.of());
        try {
            if (!Files.isRegularFile(input) || Files.size(input) > 256L * 1024 * 1024)
                throw new IllegalArgumentException("Hiányzó vagy 256 MiB-nál nagyobb PDF");
            byte[] bytes = Files.readAllBytes(input);
            if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new IllegalArgumentException("Nem támogatott PDF-fejléc");
            result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            var validator = SignedDocumentValidator.fromDocument(new FileDocument(input.toFile()));
            validator.setCertificateVerifier(new CommonCertificateVerifier());
            var probedSignatures = validator.getSignatures();
            var probedSigners = probedSignatures.stream().map(signature -> signature.getSigningCertificateToken()).toList();
            if (probedSignatures.isEmpty()) {
                result.put("status", "NO_SIGNATURES");
            } else {
                // Recreate the analyzer: the probe must not cache a verifier without EU trust data.
                try {
                    validate(SignedDocumentValidator.fromDocument(new FileDocument(input.toFile())), output, config, result, true, probedSigners);
                } catch (RetryAllLists e) {
                    result.put("listHintFallback", true);
                    validate(SignedDocumentValidator.fromDocument(new FileDocument(input.toFile())), output, config, result, false, probedSigners);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            result.put("status", "ERROR");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("elapsedMillis", (System.nanoTime()-startedNanos)/1_000_000);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), result);
        // A result is consumable only after the complete JSON has been written.
        if ("ERROR".equals(result.get("status"))) System.exit(1);
    }

    static void validate(SignedDocumentValidator validator, Path output, Path config,
                         Map<String,Object> result, boolean useHints, List<CertificateToken> probedSigners) throws Exception {
        Path pem = config.resolve("lotl-anchors.pem");
        if (!Files.isRegularFile(pem))
            throw new IllegalStateException("Nincs hitelesített LOTL-bizalmi alap: " + pem);
        var anchors = new CommonTrustedCertificateSource();
        try (var stream = Files.newInputStream(pem)) {
            var certs = CertificateFactory.getInstance("X.509").generateCertificates(stream);
            if (certs.isEmpty()) throw new IllegalStateException("Üres LOTL-bizalmi alap");
            for (var cert : certs) anchors.addCertificate(new CertificateToken((X509Certificate) cert));
        }
        String oj = Files.readString(config.resolve("oj-url.txt")).strip();
        if (!oj.startsWith("https://eur-lex.europa.eu/"))
            throw new IllegalStateException("Az OJ hivatkozásnak hivatalos EUR-Lex HTTPS URL-nek kell lennie");

        CommonsDataLoader http = new CommonsDataLoader();
        http.setUseSystemProperties(true);
        http.setTimeoutConnection(15000);
        http.setTimeoutConnectionRequest(15000);
        http.setTimeoutResponse(20000);
        http.setTimeoutSocket(20000);
        CacheStore cache = new CacheStore(CacheStore.defaultPath());
        var online = cache.new Loader(http, "trusted-lists", true);
        FileCacheDataLoader offline = new FileCacheDataLoader();
        offline.setFileCacheDirectory(cache.root.resolve("unused-offline").toFile());
        Map<String,Object> cacheInfo = new LinkedHashMap<>();
        cacheInfo.put("directory", cache.root.toString());
        cacheInfo.put("trustedListMaxAgeHours", 6);
        cacheInfo.put("certificateMaxAgeDays", 30);
        cacheInfo.put("storesValidationVerdicts", false);
        result.put("cache", cacheInfo);

        var trusted = new TrustedListsCertificateSource();
        Set<String> preferredLists = useHints ? cache.listsFor(probedSigners) : Set.of();
        cacheInfo.put("listScope", preferredLists.isEmpty() ? "ALL" : "KNOWN_SIGNERS");
        cacheInfo.put("selectedTrustedListUrls", preferredLists);
        LOTLSource lotl = new LOTLSource();
        lotl.setUrl(LOTL);
        lotl.setCertificateSource(anchors);
        lotl.setPivotSupport(true);
        lotl.setSigningCertificatesAnnouncementPredicate(new OfficialJournalSchemeInformationURI(oj));
        lotl.setTLVersions(Arrays.asList(5, 6));
        if (!preferredLists.isEmpty()) lotl.setTlPredicate(pointer -> preferredLists.contains(pointer.getTSLLocation()));
        TLValidationJob job = new TLValidationJob();
        job.setListOfTrustedListSources(lotl);
        job.setTrustedListCertificateSource(trusted);
        job.setOnlineDataLoader(online);
        job.setOfflineDataLoader(offline);
        job.setSynchronizationStrategy(new SynchronizationStrategy<TLInfo, LOTLInfo>() {
            public boolean canBeSynchronized(TLInfo info) { return usable(info) && usable(info.getParent()); }
            public boolean canBeSynchronized(LOTLInfo info) { return usable(info); }
        });
        long listsStarted = System.nanoTime();
        try { refreshLists(job); }
        catch (Exception e) { if (!preferredLists.isEmpty()) throw new RetryAllLists(); throw e; }
        cacheInfo.put("trustedListLoadMillis",(System.nanoTime()-listsStarted)/1_000_000);
        cacheInfo.put("trustedListCacheHits",cache.hits.get());
        cacheInfo.put("trustedListDownloadAttempts",cache.downloads.get());
        var summary = job.getSummary();
        List<Map<String,Object>> lists = new ArrayList<>();
        int accepted = 0;
        for (var l : summary.getLOTLInfos()) {
            lists.add(listInfo(l));
            for (var t : l.getTLInfos()) {
                lists.add(listInfo(t));
                if (usable(l) && usable(t)) accepted++;
            }
        }
        result.put("trustedLists", lists);
        result.put("acceptedTrustedLists", accepted);
        if (summary.getLOTLInfos().isEmpty() || !summary.getLOTLInfos().stream().allMatch(Main::usable)
                || accepted == 0)
            { if (!preferredLists.isEmpty()) throw new RetryAllLists();
              throw new IllegalStateException("Az EU bizalmi listái nem hitelesíthetők vagy nem aktuálisak"); }

        int trustedCount = trusted.getNumberOfCertificates();
        result.put("trustedCertificateCount", trustedCount);
        if (trustedCount == 0)
            throw new IllegalStateException("A hitelesített listákból nem töltődött be bizalmi tanúsítvány");

        var verifier = new CommonCertificateVerifier();
        verifier.setTrustedCertSources(trusted);
        var cachedCrl = new eu.europa.esig.dss.service.crl.FileCacheCRLSource(new OnlineCRLSource(http));
        cachedCrl.setFileCacheDirectory(CacheStore.privateDirectory(cache.root.resolve("crl")).toFile());
        cachedCrl.setDefaultNextUpdateDelay(300L);
        cachedCrl.setMaxNextUpdateDelay(21600L);
        verifier.setCrlSource(cachedCrl);
        var adjunct = cache.signerCertificates();
        verifier.setAdjunctCertSources(adjunct);
        cacheInfo.put("reusedSignerCertificates",adjunct.getNumberOfCertificates());
        var ocsp = new eu.europa.esig.dss.service.http.commons.OCSPDataLoader();
        ocsp.setUseSystemProperties(true);
        ocsp.setTimeoutConnection(15000);
        ocsp.setTimeoutConnectionRequest(15000);
        ocsp.setTimeoutResponse(20000);
        ocsp.setTimeoutSocket(20000);
        var cachedOcsp = new eu.europa.esig.dss.service.ocsp.FileCacheOCSPSource(new OnlineOCSPSource(ocsp));
        cachedOcsp.setFileCacheDirectory(CacheStore.privateDirectory(cache.root.resolve("ocsp")).toFile());
        cachedOcsp.setDefaultNextUpdateDelay(300L);
        cachedOcsp.setMaxNextUpdateDelay(21600L);
        verifier.setOcspSource(cachedOcsp);
        var aia = new DefaultAIASource(cache.new Loader(http,"issuer-certificates",false));
        aia.setAcceptedProtocols(List.of(Protocol.HTTP, Protocol.HTTPS));
        verifier.setAIASource(aia);
        validator.setCertificateVerifier(verifier);
        validator.setEnableEtsiValidationReport(true);
        long documentStarted = System.nanoTime();
        var reports = validator.validateDocument();
        cacheInfo.put("documentValidationMillis",(System.nanoTime()-documentStarted)/1_000_000);
        Files.writeString(output.resolve("simple-report.xml"), reports.getXmlSimpleReport());
        Files.writeString(output.resolve("detailed-report.xml"), reports.getXmlDetailedReport());
        Files.writeString(output.resolve("diagnostic-data.xml"), reports.getXmlDiagnosticData());
        Files.writeString(output.resolve("etsi-report.xml"), reports.getXmlValidationReport());
        var simple = reports.getSimpleReport();
        if (!preferredLists.isEmpty() && simple.getSignatureIdList().stream().anyMatch(id ->
                simple.getIndication(id) != eu.europa.esig.dss.enumerations.Indication.TOTAL_PASSED
                && simple.getIndication(id) != eu.europa.esig.dss.enumerations.Indication.TOTAL_FAILED))
            throw new RetryAllLists();
        Set<String> usedLists = new HashSet<>();
        for (var list : reports.getDiagnosticData().getTrustedLists()) usedLists.add(list.getUrl());
        List<Map<String,Object>> signatures = new ArrayList<>();
        for (String id : simple.getSignatureIdList()) {
            Map<String,Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("signedBy", simple.getSignedBy(id));
            s.put("indication", simple.getIndication(id));
            s.put("subIndication", simple.getSubIndication(id));
            s.put("qualification", simple.getSignatureQualification(id));
            s.put("claimedSigningTime", simple.getSigningTime(id));
            s.put("bestSignatureTime", simple.getBestSignatureTime(id));
            s.put("errors", simple.getAdESValidationErrors(id));
            s.put("warnings", simple.getAdESValidationWarnings(id));
            s.put("qualificationErrors", simple.getQualificationErrors(id));
            s.put("qualificationWarnings", simple.getQualificationWarnings(id));
            signatures.add(s);
        }
        int savedSigners = 0;
        for (var signature : validator.getSignatures()) {
            if (eu.europa.esig.dss.enumerations.Indication.TOTAL_PASSED.equals(simple.getIndication(signature.getId()))) {
                try { cache.rememberSigner(signature.getSigningCertificateToken()); cache.rememberLists(signature.getSigningCertificateToken(), usedLists); savedSigners++; }
                catch (Exception e) { cacheInfo.put("saveWarning", "Nem sikerült minden aláíró tanúsítványát menteni: " + e.getMessage()); }
            }
        }
        cacheInfo.put("savedValidSignerCertificates",savedSigners);
        cacheInfo.put("totalResourceCacheHits",cache.hits.get());
        cacheInfo.put("totalResourceDownloadAttempts",cache.downloads.get());
        result.put("signatures", signatures);
        result.put("status", "COMPLETED");
    }

    static final class RetryAllLists extends RuntimeException {}

    static void refreshLists(TLValidationJob job) {
        var workers = java.util.concurrent.Executors.newCachedThreadPool();
        job.setExecutorService(workers);
        try { job.onlineRefresh(); }
        finally { workers.shutdownNow(); }
    }

    static Map<String,Object> listInfo(TLInfo info) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("url", info.getUrl());
        row.put("usable", usable(info));
        if (info.getValidationCacheInfo() != null) {
            row.put("indication", info.getValidationCacheInfo().getIndication());
            row.put("subIndication", info.getValidationCacheInfo().getSubIndication());
        }
        if (info.getParsingCacheInfo() != null) {
            row.put("issueDate", info.getParsingCacheInfo().getIssueDate());
            row.put("nextUpdate", info.getParsingCacheInfo().getNextUpdateDate());
        }
        return row;
    }
}
