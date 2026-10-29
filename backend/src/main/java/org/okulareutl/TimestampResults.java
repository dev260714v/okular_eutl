// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import eu.europa.esig.dss.validation.reports.Reports;
import java.util.*;

/** Inventory includes invalid tokens: presence, validity and qualification are separate. */
final class TimestampResults {
    static List<Map<String,Object>> collect(Reports reports, long fileSize) {
        var result = new ArrayList<Map<String,Object>>();
        var simple = reports.getSimpleReport();
        for (var timestamp : reports.getDiagnosticData().getTimestampList()) {
            String id = timestamp.getId();
            var row = new LinkedHashMap<String,Object>();
            row.put("id", id);
            row.put("type", timestamp.getType());
            row.put("productionTime", timestamp.getProductionTime());
            row.put("producedBy", simple.getProducedBy(id));
            row.put("indication", simple.getIndication(id));
            row.put("subIndication", simple.getSubIndication(id));
            row.put("qualification", simple.getTimestampQualification(id));
            row.put("errors", simple.getAdESValidationErrors(id));
            row.put("warnings", simple.getAdESValidationWarnings(id));
            row.put("qualificationErrors", simple.getQualificationErrors(id));
            row.put("qualificationWarnings", simple.getQualificationWarnings(id));
            row.put("messageImprintFound", timestamp.isMessageImprintDataFound());
            row.put("messageImprintIntact", timestamp.isMessageImprintDataIntact());
            row.put("coversSignature", timestamp.getType() != null && timestamp.getType().coversSignature());
            row.put("timestampedSignatureIds", timestamp.getTimestampedSignatures().stream().map(s -> s.getId()).toList());
            var revision = timestamp.getPDFRevision();
            row.put("documentTimestamp", revision != null);
            row.put("coverage", revision == null ? "NOT_APPLICABLE" : DocumentCoverage.signature(
                revision.getSignatureByteRange(), revision.isSignatureByteRangeValid(), fileSize));
            result.add(row);
        }
        return result;
    }

    static boolean valid(Map<String,Object> timestamp) {
        String indication = String.valueOf(timestamp.get("indication"));
        return ("PASSED".equals(indication) || "TOTAL_PASSED".equals(indication))
            && Boolean.TRUE.equals(timestamp.get("messageImprintFound"))
            && Boolean.TRUE.equals(timestamp.get("messageImprintIntact"));
    }

    static String summarize(List<Map<String,Object>> timestamps) {
        if (timestamps.isEmpty()) return "ABSENT";
        boolean unknown = false;
        for (var timestamp : timestamps) {
            String indication = String.valueOf(timestamp.get("indication"));
            if ("FAILED".equals(indication) || "TOTAL_FAILED".equals(indication)) return "FAILED";
            if (!valid(timestamp)) unknown = true;
        }
        return unknown ? "INDETERMINATE" : "PASSED";
    }
}
