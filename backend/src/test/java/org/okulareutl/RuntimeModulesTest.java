// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ServiceLoader;
import eu.europa.esig.dss.cms.ICMSUtils;
import eu.europa.esig.dss.crl.ICRLUtils;
import eu.europa.esig.dss.validation.policy.ValidationPolicyLoader;
class RuntimeModulesTest {
    @Test void cmsImplementationIsAvailable() { assertTrue(ServiceLoader.load(ICMSUtils.class).iterator().hasNext()); }
    @Test void crlImplementationIsAvailable() { assertTrue(ServiceLoader.load(ICRLUtils.class).iterator().hasNext()); }
    @Test void defaultValidationPolicyLoads() throws Exception {
        assertNotNull(ValidationPolicyLoader.fromDefaultValidationPolicy().create());
    }
}
