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
    @Test void realRfc3161TimestampIsValidatedWithSignature() throws Exception {
        byte[] timestamped=sign(pdf("Timestamped synthetic document"),true);
        var reports=validate(timestamped,verifier(true,false));
        assertEquals(Indication.TOTAL_PASSED,signatureResult(timestamped,reports).get("indication"));
        assertFalse(reports.getDiagnosticData().getSignatures().get(0).getSignatureTimestamps().isEmpty());
    }
}
