// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.pdf.*;
import eu.europa.esig.dss.pdf.pdfbox.*;
import eu.europa.esig.dss.pdf.PdfMemoryUsageSetting;
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxDefaultObjectFactory;
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator;
import eu.europa.esig.dss.signature.resources.TempFileResourcesHandlerBuilder;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Streaming input and file-backed PDF parsing; never allocate the complete PDF. */
final class PdfInput {
    // DSS 6.5.1 uses signed 32-bit offsets for PDF revision ByteRanges.
    static final long MAX_BYTES = Integer.MAX_VALUE;
    record Fingerprint(long size, String sha256) {}

    static Fingerprint inspect(Path input) throws Exception {
        if (!Files.isRegularFile(input) || Files.size(input) > MAX_BYTES)
            throw new IllegalArgumentException("Hiányzó vagy a 2 GiB - 1 bájtos határt meghaladó PDF");
        var digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (var stream = Files.newInputStream(input)) {
            byte[] header = stream.readNBytes(5);
            if (!Arrays.equals(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException("Nem támogatott PDF-fejléc");
            digest.update(header); size = header.length;
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                size += read;
                if (size > MAX_BYTES) throw new IllegalArgumentException("A PDF meghaladja a 2 GiB - 1 bájtos határt");
                digest.update(buffer, 0, read);
            }
        }
        return new Fingerprint(size, HexFormat.of().formatHex(digest.digest()));
    }

    static PdfBoxDefaultObjectFactory factory(Path temporary) throws IOException {
        CacheStore.privateDirectory(temporary);
        var factory = new PdfBoxDefaultObjectFactory() {
            @Override public PDFSignatureService newPAdESSignatureService() { return service(PDFServiceMode.SIGNATURE); }
            @Override public PDFSignatureService newContentTimestampService() { return service(PDFServiceMode.CONTENT_TIMESTAMP); }
            @Override public PDFSignatureService newSignatureTimestampService() { return service(PDFServiceMode.SIGNATURE_TIMESTAMP); }
            @Override public PDFSignatureService newArchiveTimestampService() { return service(PDFServiceMode.ARCHIVE_TIMESTAMP); }
            private PDFSignatureService service(PDFServiceMode mode) {
                return configure(new PdfBoxSignatureService(mode, getSignatureDrawerFactory()) {
                    @Override protected PdfDocumentReader loadPdfDocumentReader(DSSDocument document, char[] password) throws IOException {
                        if (document instanceof FileDocument) return super.loadPdfDocumentReader(document, password);
                        // DSS revision views are streams, even for a FileDocument input.
                        // PDFBox otherwise buffers each entire revision in heap memory.
                        Path revision = Files.createTempFile(temporary, "revision-", ".pdf");
                        try {
                            try (var input = document.openStream(); var output = Files.newOutputStream(revision)) {
                                input.transferTo(output);
                            }
                            return new PdfBoxDocumentReader(new FileDocument(revision.toFile()),
                                    password == null ? null : new String(password), PdfMemoryUsageSetting.fileOnly()) {
                                @Override public void close() throws IOException {
                                    try { super.close(); } finally { Files.deleteIfExists(revision); }
                                }
                            };
                        } catch (IOException | RuntimeException e) { Files.deleteIfExists(revision); throw e; }
                    }
                });
            }
        };
        factory.setPdfMemoryUsageSetting(PdfMemoryUsageSetting.fileOnly());
        factory.setResourcesHandlerBuilder(new TempFileResourcesHandlerBuilder()
            .setTempFileDirectory(CacheStore.privateDirectory(temporary).toFile()));
        return factory;
    }

    static PDFDocumentValidator validator(Path input, Path temporary) throws IOException {
        var validator = new PDFDocumentValidator(new FileDocument(input.toFile()) {
            @Override public InputStream openStream() {
                return new BufferedInputStream(super.openStream(), 1024 * 1024);
            }
        });
        validator.setPdfObjFactory(factory(temporary));
        return validator;
    }
}
