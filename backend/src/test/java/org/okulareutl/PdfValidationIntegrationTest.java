// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import eu.europa.esig.dss.enumerations.*;
import eu.europa.esig.dss.model.*;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.pades.*;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.*;
import org.bouncycastle.tsp.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PDF signing, RFC3161 timestamping and DSS validation. All keys, certificates,
 * CRLs and documents are generated in memory; no production data or network needed.
 * Synthetic trust is supplied ONLY to these tests, never to Main's EU trust configuration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfValidationIntegrationTest {
    @TempDir Path cacheDir;
    KeyPair caKey, signerKey, tsaKey;
    X509Certificate ca, signer, tsa;
    byte[] goodCrl, revokedCrl, signed;
    final Instant now = Instant.now();

    KeyPair keys() throws Exception { var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair(); }
    X509Certificate certificate(String name, int serial, KeyPair key, boolean root, boolean timestamp) throws Exception {
        X500Name issuer=new X500Name("CN=Synthetic Test CA");
        var builder=new JcaX509v3CertificateBuilder(issuer,BigInteger.valueOf(serial),Date.from(now.minusSeconds(86400)),
            Date.from(now.plusSeconds(86400*30)),new X500Name("CN="+name),key.getPublic());
        var utils=new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(root));
        builder.addExtension(Extension.keyUsage,true,new KeyUsage(root ? KeyUsage.keyCertSign|KeyUsage.cRLSign : KeyUsage.digitalSignature|KeyUsage.nonRepudiation));
        builder.addExtension(Extension.subjectKeyIdentifier,false,utils.createSubjectKeyIdentifier(key.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier,false,utils.createAuthorityKeyIdentifier(caKey.getPublic()));
        if(timestamp)builder.addExtension(Extension.extendedKeyUsage,true,new org.bouncycastle.asn1.x509.ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKey.getPrivate())));
    }
    byte[] crl(boolean revoked) throws Exception {
        var builder=new X509v2CRLBuilder(new X500Name(ca.getSubjectX500Principal().getName()),Date.from(now.minusSeconds(3600)));
        builder.setNextUpdate(Date.from(now.plusSeconds(3600)));
        builder.addExtension(Extension.authorityKeyIdentifier,false,new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(caKey.getPublic()));
        builder.addExtension(Extension.cRLNumber,false,new CRLNumber(BigInteger.ONE));
        if(revoked)builder.addCRLEntry(signer.getSerialNumber(),Date.from(now.minusSeconds(7200)),CRLReason.keyCompromise);
        return builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKey.getPrivate())).getEncoded();
    }
    CommonCertificateVerifier verifier(boolean trust, boolean revoked) throws Exception {
        var verifier=new CommonCertificateVerifier();
        if(trust) { var roots=new CommonTrustedCertificateSource();roots.addCertificate(new CertificateToken(ca));verifier.setTrustedCertSources(roots); }
        verifier.setCrlSource(new ExternalResourcesCRLSource(new ByteArrayInputStream(revoked?revokedCrl:goodCrl)));
        return verifier;
    }
    @BeforeAll void fixtures() throws Exception {
        caKey=keys();signerKey=keys();tsaKey=keys();
        ca=certificate("Synthetic Test CA",1,caKey,true,false);
        signer=certificate("Synthetic Signer",2,signerKey,false,false);
        tsa=certificate("Synthetic Timestamp",3,tsaKey,false,true);
        goodCrl=crl(false);revokedCrl=crl(true);
        signed=sign(pdf("SYNTHETIC_MARKER_A"),false);
    }
    byte[] pdf(String title) throws Exception {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()) {
            document.addPage(new PDPage());document.getDocumentInformation().setTitle(title);document.save(out,org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);return out.toByteArray();
        }
    }
    byte[] sign(byte[] input, boolean timestamp) throws Exception {
        var service=new PAdESService(verifier(true,false));
        if(timestamp)service.setTspSource((algorithm,digest)-> {
            try {
                var calculators=new JcaDigestCalculatorProviderBuilder().build();
                var signerInfo=new JcaSignerInfoGeneratorBuilder(calculators).build(new JcaContentSignerBuilder("SHA256withRSA").build(tsaKey.getPrivate()),tsa);
                var generator=new TimeStampTokenGenerator(signerInfo,calculators.get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1)),new ASN1ObjectIdentifier("1.2.3.4.1"));
                generator.addCertificates(new JcaCertStore(List.of(tsa,ca)));
                var requestGenerator=new TimeStampRequestGenerator();requestGenerator.setCertReq(true);
                var request=requestGenerator.generate(new ASN1ObjectIdentifier(algorithm.getOid()),digest);
                return new TimestampBinary(generator.generate(request,BigInteger.TEN,Date.from(now.minusSeconds(30))).getEncoded());
            } catch(Exception e) { throw new DSSException(e); }
        });
        var parameters=new PAdESSignatureParameters();
        parameters.setSigningCertificate(new CertificateToken(signer));
        parameters.setCertificateChain(List.of(new CertificateToken(signer),new CertificateToken(ca)));
        parameters.setSignatureLevel(timestamp?SignatureLevel.PAdES_BASELINE_T:SignatureLevel.PAdES_BASELINE_B);
        parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
        parameters.bLevel().setSigningDate(Date.from(now.minusSeconds(120)));
        var document=new InMemoryDocument(input,"synthetic.pdf",MimeTypeEnum.PDF);
        var data=service.getDataToSign(document,parameters);
        var rsa=Signature.getInstance("SHA256withRSA");rsa.initSign(signerKey.getPrivate());rsa.update(data.getBytes());
        try(var stream=service.signDocument(document,parameters,new SignatureValue(SignatureAlgorithm.RSA_SHA256,rsa.sign())).openStream()) { return stream.readAllBytes(); }
    }
    Reports validate(byte[] pdf, CommonCertificateVerifier verifier) {
        var validator=SignedDocumentValidator.fromDocument(new InMemoryDocument(pdf,"synthetic.pdf",MimeTypeEnum.PDF));
        validator.setCertificateVerifier(verifier);return validator.validateDocument();
    }
    Map<String,Object> signatureResult(byte[] pdf, Reports reports) {
        var results=Main.signatureResults(reports,pdf.length);assertEquals(1,results.size());return results.get(0);
    }
    @Test void validSignedPdfHasFullCoverage() throws Exception {
        var result=signatureResult(signed,validate(signed,verifier(true,false)));
        assertEquals(Indication.TOTAL_PASSED,result.get("indication"));assertEquals("FULL",result.get("coverage"));
        assertEquals("FULL",DocumentCoverage.summarize(List.of(result)).get("status"));
    }
    @Test void tamperedCoveredBytesFailHashEvenAfterSignerIsCached() throws Exception {
        var store=new CacheStore(cacheDir);store.rememberSigner(new CertificateToken(signer));
        byte[] modified=signed.clone();byte[] marker="SYNTHETIC_MARKER_A".getBytes(StandardCharsets.US_ASCII);
        int found=-1;for(int i=0;i<=modified.length-marker.length;i++)if(Arrays.equals(modified,i,i+marker.length,marker,0,marker.length)){found=i;break;}
        assertTrue(found>=0);modified[found+marker.length-1]='B';
        var verifier=verifier(true,false);verifier.setAdjunctCertSources(store.signerCertificates(List.of(new CertificateToken(signer))));
        var result=signatureResult(modified,validate(modified,verifier));
        assertEquals(Indication.TOTAL_FAILED,result.get("indication"));assertEquals(SubIndication.HASH_FAILURE,result.get("subIndication"));
    }
    @Test void revokedSignerNeverPasses() throws Exception {
        var result=signatureResult(signed,validate(signed,verifier(true,true)));
        assertNotEquals(Indication.TOTAL_PASSED,result.get("indication"));
        assertTrue(String.valueOf(result.get("subIndication")).contains("REVOKED"));
    }
    @Test void cachedSignerDoesNotRepairMissingTrustChain() throws Exception {
        var store=new CacheStore(cacheDir);store.rememberSigner(new CertificateToken(signer));
        var verifier=verifier(false,false);verifier.setAdjunctCertSources(store.signerCertificates(List.of(new CertificateToken(signer))));
        var result=signatureResult(signed,validate(signed,verifier));
        assertEquals(Indication.INDETERMINATE,result.get("indication"));
        assertEquals(SubIndication.NO_CERTIFICATE_CHAIN_FOUND,result.get("subIndication"));
    }
    @Test void laterUnsignedRevisionIsProminentlyPartial() throws Exception {
        byte[] revision;
        try(var document=Loader.loadPDF(signed);var out=new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("Later synthetic revision");
            document.getDocumentInformation().getCOSObject().setNeedToBeUpdated(true);
            document.saveIncremental(out);revision=out.toByteArray();
        }
        var result=signatureResult(revision,validate(revision,verifier(true,false)));
        assertEquals("PARTIAL",result.get("coverage"));
        assertNotEquals("FULL",DocumentCoverage.summarize(List.of(result)).get("status"));
    }
    @Test void secondDocumentFromSameCachedSignerIsStillVerified() throws Exception {
        var store=new CacheStore(cacheDir);store.rememberSigner(new CertificateToken(signer));
        byte[] second=sign(pdf("Second synthetic document"),false);
        var verifier=verifier(true,false);verifier.setAdjunctCertSources(new CacheStore(cacheDir).signerCertificates(List.of(new CertificateToken(signer))));
        assertEquals(Indication.TOTAL_PASSED,signatureResult(second,validate(second,verifier)).get("indication"));
        assertFalse(Arrays.equals(signed,second));
    }
    eu.europa.esig.dss.spi.x509.tsp.TSPSource timestampSource() {
        return (algorithm, digest) -> {
            try {
                var calculators = new JcaDigestCalculatorProviderBuilder().build();
                var info = new JcaSignerInfoGeneratorBuilder(calculators).build(new JcaContentSignerBuilder("SHA256withRSA").build(tsaKey.getPrivate()), tsa);
                var generator = new TimeStampTokenGenerator(info, calculators.get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1)), new ASN1ObjectIdentifier("1.2.3.4.1"));
                generator.addCertificates(new JcaCertStore(List.of(tsa, ca)));
                var request = new TimeStampRequestGenerator(); request.setCertReq(true);
                return new TimestampBinary(generator.generate(request.generate(new ASN1ObjectIdentifier(algorithm.getOid()), digest), BigInteger.TEN,
                    Date.from(now.minusSeconds(10))).getEncoded());
            } catch (Exception e) { throw new DSSException(e); }
        };
    }
    byte[] documentTimestamp(byte[] input) throws Exception {
        var service = new PAdESService(verifier(true, false));
        service.setTspSource(timestampSource());
        try (var stream = service.timestamp(new InMemoryDocument(input, "synthetic.pdf", MimeTypeEnum.PDF),
                new PAdESTimestampParameters(DigestAlgorithm.SHA256)).openStream()) { return stream.readAllBytes(); }
    }
    @Test void largeSignedFileUsesDiskBackedParsing() throws Exception {
        Path input = cacheDir.resolve("large-source.pdf");
        try (var file = new RandomAccessFile(input.toFile(), "rw")) {
            file.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            long catalog = file.getFilePointer();
            file.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            long pages = file.getFilePointer();
            file.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            long page = file.getFilePointer();
            file.write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            long padding = file.getFilePointer();
            long length = 270L * 1024 * 1024;
            file.write(("4 0 obj\n<< /Length " + length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
            file.seek(file.getFilePointer() + length); file.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            long xref = file.getFilePointer();
            file.write("xref\n0 5\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
            for (long offset : new long[]{catalog, pages, page, padding})
                file.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
            file.write(("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        }
        var service = new PAdESService(verifier(true, false));
        service.setPdfObjFactory(PdfInput.factory(cacheDir.resolve("large-work")));
        var parameters = new PAdESSignatureParameters();
        parameters.setSigningCertificate(new CertificateToken(signer));
        parameters.setCertificateChain(List.of(new CertificateToken(signer), new CertificateToken(ca)));
        parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
        parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
        parameters.bLevel().setSigningDate(Date.from(now.minusSeconds(120)));
        var document = new FileDocument(input.toFile());
        var data = service.getDataToSign(document, parameters);
        var rsa = Signature.getInstance("SHA256withRSA"); rsa.initSign(signerKey.getPrivate()); rsa.update(data.getBytes());
        var output = service.signDocument(document, parameters, new SignatureValue(SignatureAlgorithm.RSA_SHA256, rsa.sign()));
        Path signedFile = cacheDir.resolve("large-signed.pdf"); output.save(signedFile.toString());
        assertTrue(java.nio.file.Files.size(signedFile) > 256L * 1024 * 1024);
        var validator = PdfInput.validator(signedFile, cacheDir.resolve("large-validation"));
        validator.setCertificateVerifier(verifier(true, false));
        var reports = validator.validateDocument();
        assertEquals(Indication.TOTAL_PASSED, Main.signatureResults(reports, java.nio.file.Files.size(signedFile)).get(0).get("indication"));
    }
    @Test void noTimestampIsExplicitlyAbsentEvenForValidSignature() throws Exception {
        var timestamps = TimestampResults.collect(validate(signed, verifier(true, false)), signed.length);
        assertTrue(timestamps.isEmpty()); assertEquals("ABSENT", TimestampResults.summarize(timestamps));
    }
    @Test void documentTimestampAloneIsValidatedAndCoversTheFile() throws Exception {
        byte[] input = documentTimestamp(pdf("Timestamp only"));
        var reports = validate(input, verifier(true, false));
        assertTrue(Main.signatureResults(reports, input.length).isEmpty());
        var timestamps = TimestampResults.collect(reports, input.length);
        assertEquals(1, timestamps.size()); assertEquals("PASSED", TimestampResults.summarize(timestamps));
        assertEquals("FULL", timestamps.get(0).get("coverage"));
        assertEquals("FULL", DocumentCoverage.summarize(List.of(), timestamps).get("status"));
    }
    @Test void documentTimestampProtectsLatestRevisionAfterSignature() throws Exception {
        byte[] input = documentTimestamp(signed);
        var reports = validate(input, verifier(true, false));
        var signatures = Main.signatureResults(reports, input.length);
        var timestamps = TimestampResults.collect(reports, input.length);
        assertEquals("PARTIAL", signatures.get(0).get("coverage"));
        assertEquals("PASSED", TimestampResults.summarize(timestamps));
        assertEquals("FULL", DocumentCoverage.summarize(signatures, timestamps).get("status"));
        assertFalse(((List<?>)DocumentCoverage.summarize(signatures, timestamps).get("coveringTimestampIds")).isEmpty());
    }
    @Test void untrustedTimestampCannotEstablishCoverage() throws Exception {
        byte[] input = documentTimestamp(pdf("Untrusted timestamp"));
        var timestamps = TimestampResults.collect(validate(input, verifier(false, false)), input.length);
        assertEquals(1, timestamps.size()); assertFalse(TimestampResults.valid(timestamps.get(0)));
        assertNotEquals("FULL", DocumentCoverage.summarize(List.of(), timestamps).get("status"));
    }
    @Test void tamperedTimestampIsReportedAsFailed() throws Exception {
        byte[] input = documentTimestamp(pdf("Tampered timestamp"));
        try (var document = Loader.loadPDF(input)) {
            var signature = document.getSignatureDictionaries().get(0);
            byte[] token = ASN1Primitive.fromByteArray(new ASN1InputStream(signature.getContents(input)).readObject().getEncoded()).getEncoded();
            token[token.length - 1] ^= 1; // TSA signature, outside the timestamped byte ranges.
            String hex = HexFormat.of().formatHex(token);
            int offset = signature.getByteRange()[1] + 1;
            System.arraycopy(hex.getBytes(StandardCharsets.US_ASCII), 0, input, offset, hex.length());
        }
        var timestamps = TimestampResults.collect(validate(input, verifier(true, false)), input.length);
        assertEquals("FAILED", TimestampResults.summarize(timestamps));
        assertNotEquals("FULL", DocumentCoverage.summarize(List.of(), timestamps).get("status"));
    }
    @Test void timestampMessageImprintMismatchCannotPass() throws Exception {
        byte[] input = documentTimestamp(pdf("SYNTHETIC_MARKER_A"));
        byte[] marker = "SYNTHETIC_MARKER_A".getBytes(StandardCharsets.US_ASCII);
        boolean changed = false;
        for (int i = 0; i <= input.length - marker.length; i++) {
            if (Arrays.equals(input, i, i + marker.length, marker, 0, marker.length)) {
                input[i + marker.length - 1] = 'B'; changed = true; break;
            }
        }
        assertTrue(changed);
        var timestamps = TimestampResults.collect(validate(input, verifier(true, false)), input.length);
        assertEquals(1, timestamps.size()); assertFalse((Boolean)timestamps.get(0).get("messageImprintIntact"));
        assertFalse(TimestampResults.valid(timestamps.get(0)));
    }
    @Test void revokedTsaCannotEstablishTrustedTime() throws Exception {
        byte[] input = documentTimestamp(pdf("Revoked TSA"));
        var crlBuilder = new X509v2CRLBuilder(new X500Name(ca.getSubjectX500Principal().getName()), Date.from(now.minusSeconds(3600)));
        crlBuilder.setNextUpdate(Date.from(now.plusSeconds(3600)));
        crlBuilder.addExtension(Extension.authorityKeyIdentifier, false, new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(caKey.getPublic()));
        crlBuilder.addCRLEntry(tsa.getSerialNumber(), Date.from(now.minusSeconds(7200)), CRLReason.keyCompromise);
        byte[] crl = crlBuilder.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKey.getPrivate())).getEncoded();
        var verifier = verifier(true, false);
        verifier.setCrlSource(new ExternalResourcesCRLSource(new ByteArrayInputStream(crl)));
        var timestamps = TimestampResults.collect(validate(input, verifier), input.length);
        assertEquals(1, timestamps.size()); assertFalse(TimestampResults.valid(timestamps.get(0)));
    }
    @Test void unsignedChangesAfterDocumentTimestampRemainUncovered() throws Exception {
        byte[] input = documentTimestamp(signed), revision;
        try (var document = Loader.loadPDF(input); var out = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("Later revision");
            document.getDocumentInformation().getCOSObject().setNeedToBeUpdated(true);
            document.saveIncremental(out); revision = out.toByteArray();
        }
        var reports = validate(revision, verifier(true, false));
        var timestamps = TimestampResults.collect(reports, revision.length);
        assertEquals("PARTIAL", timestamps.get(0).get("coverage"));
        assertNotEquals("FULL", DocumentCoverage.summarize(Main.signatureResults(reports, revision.length), timestamps).get("status"));
    }
    @Test void realRfc3161TimestampIsValidatedWithSignature() throws Exception {
        byte[] timestamped=sign(pdf("Timestamped synthetic document"),true);
        var reports=validate(timestamped,verifier(true,false));
        assertEquals(Indication.TOTAL_PASSED,signatureResult(timestamped,reports).get("indication"));
        assertFalse(reports.getDiagnosticData().getSignatures().get(0).getSignatureTimestamps().isEmpty());
        var timestamps = TimestampResults.collect(reports, timestamped.length);
        assertEquals(1, timestamps.size()); assertEquals("PASSED", TimestampResults.summarize(timestamps));
        assertTrue((Boolean)timestamps.get(0).get("coversSignature"));
        assertFalse(((List<?>)signatureResult(timestamped, reports).get("timestampIds")).isEmpty());
        assertNotNull(timestamps.get(0).get("productionTime"));
    }
}
