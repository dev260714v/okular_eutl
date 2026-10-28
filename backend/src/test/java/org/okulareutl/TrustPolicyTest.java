// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import eu.europa.esig.dss.model.job.*;
import eu.europa.esig.dss.model.tsl.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class TrustPolicyTest {
    @SuppressWarnings("unchecked")
    static <T> T record(Class<T> type, java.util.Map<String,Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> {
            if (values.containsKey(m.getName())) return values.get(m.getName());
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == int.class) return 0;
            return null;
        });
    }
    static TLInfo list(boolean valid, long issueOffset, long nextOffset, String status) {
        var dl = record(DownloadInfoRecord.class, java.util.Map.of("getStatusName",status));
        var parsing = record(TLParsingInfoRecord.class, java.util.Map.of(
                "getIssueDate", new Date(System.currentTimeMillis()+issueOffset),
                "getNextUpdateDate", new Date(System.currentTimeMillis()+nextOffset)));
        var validation = record(ValidationInfoRecord.class, java.util.Map.of("isValid",valid));
        return new TLInfo(dl, parsing, validation, "https://example.invalid/list.xml");
    }
    @Test void acceptsCurrentAuthenticatedList() { assertTrue(Main.usable(list(true,-60000,60000,"SYNCHRONIZED"))); }
    @Test void acceptsAuthenticatedListBeforeSynchronization() { assertTrue(Main.usable(list(true,-60000,60000,"DESYNCHRONIZED"))); }
    @Test void rejectsUnfetchedList() { assertFalse(Main.usable(list(true,-60000,60000,"REFRESH_NEEDED"))); }
    @Test void rejectsInvalidSignature() { assertFalse(Main.usable(list(false,-60000,60000,"SYNCHRONIZED"))); }
    @Test void rejectsExpiredList() { assertFalse(Main.usable(list(true,-120000,-60000,"SYNCHRONIZED"))); }
    @Test void rejectsFutureList() { assertFalse(Main.usable(list(true,60000,120000,"SYNCHRONIZED"))); }
    @Test void rejectsDownloadFailure() { assertFalse(Main.usable(list(true,-60000,60000,"ERROR"))); }
    @Test void rejectsMissingList() { assertFalse(Main.usable(null)); }
}
