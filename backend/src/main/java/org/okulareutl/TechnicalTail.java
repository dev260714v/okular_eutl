// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Allowlist v1: only terminal whitespace or a UUID MIME closing boundary.
 * Never interprets arbitrary data after EOF as harmless. Does not establish legal effects.
 */
final class TechnicalTail {
    static final int LIMIT = 4096;
    static Map<String,Object> inspect(Path input, long end) {
        try (var file = new RandomAccessFile(input.toFile(), "r")) {
            long length = file.length(), count = length - end;
            if (end < 16 || count <= 0 || count > LIMIT) return Map.of();
            byte[] tail = new byte[(int)count]; file.seek(end); file.readFully(tail);
            String text = new String(tail, StandardCharsets.ISO_8859_1);
            String kind;
            if (text.matches("[\\x09\\x0A\\x0C\\x0D\\x20]+")) kind = "WHITESPACE";
            else if (text.matches("\\r?\\n--[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}--\\r?\\n(?:EOF\\r?\\n)?")) kind = "MIME_UUID_CLOSING_BOUNDARY";
            else return Map.of();
            // Require an exact final PDF trailer, a bounded startxref and its real target.
            int n = (int)Math.min(end, 1024); byte[] footer = new byte[n];
            file.seek(end-n); file.readFully(footer);
            var matcher = java.util.regex.Pattern.compile("(?:^|[\\r\\n])startxref[\\r\\n ]+([0-9]+)[\\r\\n ]+%%EOF[\\t\\r\\n\\f ]*\\z")
                .matcher(new String(footer, StandardCharsets.ISO_8859_1));
            if (!matcher.find()) return Map.of();
            long xref = Long.parseLong(matcher.group(1));
            if (xref < 0 || xref >= end-n+matcher.start()) return Map.of();
            file.seek(xref); byte[] target = new byte[(int)Math.min(128,end-xref)]; file.readFully(target);
            String head = new String(target, StandardCharsets.ISO_8859_1);
            if (!head.startsWith("xref") && !head.matches("(?s)[0-9]+[ ]+[0-9]+[ ]+obj\\b.*")) return Map.of();
            return Map.of("policy", "TECHNICAL_TAIL_V1", "kind", kind, "unsignedBytes", count,
                "signedRevisionEnd", end, "byteCoverage", "PARTIAL", "contentAssessment", "ALLOWLISTED_TRAILING_DATA");
        } catch (Exception e) { return Map.of(); } // Fail closed; ordinary coverage warning remains.
    }
    static Map<String,Object> apply(Path input, List<Map<String,Object>> signatures, Map<String,Object> coverage) {
        var result = new LinkedHashMap<String,Object>(coverage);
        result.putIfAbsent("assessment", coverage.get("status"));
        if (!"PARTIAL".equals(coverage.get("status"))) return result;
        for (var signature : signatures) {
            if (!"TOTAL_PASSED".equals(String.valueOf(signature.get("indication")))
                    || !"PARTIAL".equals(signature.get("coverage"))) continue;
            Object end = signature.get("signedRevisionEnd");
            if (!(end instanceof Number)) continue;
            var detail = inspect(input, ((Number)end).longValue());
            if (!detail.isEmpty()) {
                result.put("assessment", "TECHNICAL_ONLY"); result.put("technicalTail", detail);
                result.put("assessedSignatureId", signature.get("id")); return result;
            }
        }
        return result;
    }
}
