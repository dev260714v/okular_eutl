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
        return summarize(signatures, List.of());
    }

    static Map<String,Object> summarize(List<Map<String,Object>> signatures, List<Map<String,Object>> timestamps) {
        var covering = new ArrayList<String>();
        var coveringTimestamps = new ArrayList<String>();
        boolean unknown = signatures.isEmpty();
        for (var signature : signatures) {
            String coverage = (String)signature.get("coverage");
            if ("FULL".equals(coverage) && "TOTAL_PASSED".equals(String.valueOf(signature.get("indication"))))
                covering.add(String.valueOf(signature.get("id")));
            if (!"PARTIAL".equals(coverage)
                    && !("FULL".equals(coverage) && "TOTAL_PASSED".equals(String.valueOf(signature.get("indication"))))) unknown = true;
        }
        for (var timestamp : timestamps) {
            if (!Boolean.TRUE.equals(timestamp.get("documentTimestamp"))) continue;
            String coverage = String.valueOf(timestamp.get("coverage"));
            if ("FULL".equals(coverage) && TimestampResults.valid(timestamp))
                coveringTimestamps.add(String.valueOf(timestamp.get("id")));
            else if (!"PARTIAL".equals(coverage)) unknown = true;
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("status", !covering.isEmpty() || !coveringTimestamps.isEmpty() ? "FULL" : unknown ? "UNKNOWN" : "PARTIAL");
        result.put("coveringSignatureIds", covering);
        result.put("coveringTimestampIds", coveringTimestamps);
        // A later timestamp proves existence, not the original signer's approval of
        // intervening changes. Keep the validity verdict, but disclose this case.
        var earlier = signatures.stream().filter(s -> "PARTIAL".equals(s.get("coverage"))
            && "TOTAL_PASSED".equals(String.valueOf(s.get("indication"))))
            .map(s -> String.valueOf(s.get("id"))).toList();
        if (covering.isEmpty() && !coveringTimestamps.isEmpty() && !earlier.isEmpty()) {
            result.put("assessment", "TIMESTAMP_ONLY");
            result.put("earlierSignatureIds", earlier);
        }
        return result;
    }
}
