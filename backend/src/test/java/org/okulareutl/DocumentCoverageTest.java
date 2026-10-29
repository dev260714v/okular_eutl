// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DocumentCoverageTest {
    static List<BigInteger> range(long... values) { return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList(); }
    @Test void exactCurrentRevisionIsFull() { assertEquals("FULL", DocumentCoverage.signature(range(0,100,200,800),true,1000)); }
    @Test void unsignedAppendedBytesArePartial() { assertEquals("PARTIAL", DocumentCoverage.signature(range(0,100,200,800),true,1001)); }
    @Test void badRangesAreUnknown() {
        for(var r : List.of(range(1,100,200,800),range(0,-1,200,800),range(0,300,200,800),range(0,100,200,801),range(0,100,200)))
            assertEquals("UNKNOWN",DocumentCoverage.signature(r,true,1000));
        assertEquals("UNKNOWN",DocumentCoverage.signature(range(0,100,200,800),false,1000));
    }
    @Test void untrustedLatestSignatureCannotMakeCoverageFull() {
        var signatures=List.<Map<String,Object>>of(Map.of("id","old","coverage","PARTIAL","indication","TOTAL_PASSED"),Map.of("id","new","coverage","FULL","indication","INDETERMINATE"));
        assertEquals("UNKNOWN",DocumentCoverage.summarize(signatures).get("status"));
    }
    @Test void oneValidLatestSignatureCanCoverEarlierPartialSignatures() {
        var signatures=List.<Map<String,Object>>of(Map.of("id","old","coverage","PARTIAL","indication","TOTAL_PASSED"),Map.of("id","new","coverage","FULL","indication","TOTAL_PASSED"));
        assertEquals("FULL",DocumentCoverage.summarize(signatures).get("status"));
    }
    @Test void missingCoverageRemainsUnknown() { assertEquals("UNKNOWN", DocumentCoverage.summarize(List.of(Map.of("id","x","indication","TOTAL_PASSED"))).get("status")); }
    @Test void nearTwoGiBCoverageDoesNotOverflow() {
        assertEquals("FULL", DocumentCoverage.signature(range(0,2147470000L,2147480000L,3647), true, Integer.MAX_VALUE));
    }

}
