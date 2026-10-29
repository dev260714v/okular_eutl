// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import java.math.BigInteger;
import java.util.*;

/** Coverage is distinct from cryptographic validity and EU qualification.
 * A FULL signature covers the current revision except its own CMS /Contents gap.
 * Later signatures need not make earlier signatures cover the latest revision.
 */
final class DocumentCoverage {
    static String signature(List<BigInteger> range, boolean valid, long fileSize) {
        if (!valid || range == null || range.size() != 4 || range.stream().anyMatch(Objects::isNull)) return "UNKNOWN";
        var zero = BigInteger.ZERO;
        var size = BigInteger.valueOf(fileSize);
        if (!range.get(0).equals(zero) || range.get(1).signum() <= 0
                || range.get(2).compareTo(range.get(1)) <= 0 || range.get(3).signum() < 0) return "UNKNOWN";
        var end = range.get(2).add(range.get(3));
        if (end.compareTo(size) > 0) return "UNKNOWN";
        return end.equals(size) ? "FULL" : "PARTIAL";
    }

    static Map<String,Object> summarize(List<Map<String,Object>> signatures) {
        var covering = new ArrayList<String>();
        boolean unknown = signatures.isEmpty();
        for (var signature : signatures) {
            String coverage = (String)signature.get("coverage");
            if ("FULL".equals(coverage) && "TOTAL_PASSED".equals(String.valueOf(signature.get("indication"))))
                covering.add(String.valueOf(signature.get("id")));
            if (!"PARTIAL".equals(coverage)
                    && !("FULL".equals(coverage) && "TOTAL_PASSED".equals(String.valueOf(signature.get("indication"))))) unknown = true;
        }
        return Map.of("status", !covering.isEmpty() ? "FULL" : unknown ? "UNKNOWN" : "PARTIAL",
            "coveringSignatureIds", covering);
    }
}
