// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TechnicalTailTest {
    @TempDir Path dir;
    static final String PDF="%PDF-1.7\nxref\n0 1\n0000000000 65535 f\ntrailer\n<< /Size 1 >>\nstartxref\n9\n%%EOF\n";
    Map<String,Object> inspect(String suffix) throws Exception {
        Path p=dir.resolve("input.pdf");Files.writeString(p,PDF+suffix,StandardCharsets.ISO_8859_1);
        return TechnicalTail.inspect(p,PDF.length());
    }
    @Test void narrowAllowlist() throws Exception {
        assertEquals("WHITESPACE",inspect(" \r\n\t").get("kind"));
        assertEquals(49L,inspect("\r\n--ddb3b78a-ecf1-4b7e-96d8-31c4b59f7d20--\r\nEOF\r\n").get("unsignedBytes"));
        for(String suffix:List.of("", "EOF", "\n1 0 obj <<>> endobj", "\nstartxref\n9\n%%EOF", " ".repeat(4097), "\u0000", "\n--arbitrary--\n", "\n--ddb3b78a-ecf1-4b7e-96d8-31c4b59f7d20--\nPDF"))
            assertTrue(inspect(suffix).isEmpty(),suffix);
    }
    @Test void malformedFooterRejected() throws Exception {
        Path p=dir.resolve("broken.pdf");String broken=PDF.replace("startxref\n9", "startxref\n0");
        Files.writeString(p,broken+" ");assertTrue(TechnicalTail.inspect(p,broken.length()).isEmpty());
    }
    @Test void onlyValidSignaturesCanEnableException() throws Exception {
        Path p=dir.resolve("test.pdf");Files.writeString(p,PDF+" ");
        for(String state:List.of("INDETERMINATE","TOTAL_FAILED")) {
            var sig=List.<Map<String,Object>>of(Map.of("id","s","indication",state,"coverage","PARTIAL","signedRevisionEnd",PDF.length()));
            assertNotEquals("TECHNICAL_ONLY",TechnicalTail.apply(p,sig,Map.of("status","PARTIAL")).get("assessment"));
        }
        var sig=List.<Map<String,Object>>of(Map.of("id","s","indication","TOTAL_PASSED","coverage","PARTIAL","signedRevisionEnd",PDF.length()));
        var result=TechnicalTail.apply(p,sig,Map.of("status","PARTIAL"));
        assertEquals("PARTIAL",result.get("status"));assertEquals("TECHNICAL_ONLY",result.get("assessment"));
    }
}
