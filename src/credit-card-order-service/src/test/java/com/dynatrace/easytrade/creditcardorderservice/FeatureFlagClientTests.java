package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.FeatureFlag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the fail-safe behaviour of the feature-flag client. In the test environment the
 * feature-flag-service env vars are unset, so the underlying lookup cannot succeed -- which
 * is exactly the "dependency unavailable" scenario the fail-safe path must handle. The client
 * must degrade to a disabled flag instead of throwing, so a flag-service outage never breaks
 * the request path (payments keep serving with the feature simply treated as off).
 */
public class FeatureFlagClientTests {

    @Test
    void returnsDisabledFlagInsteadOfThrowingWhenServiceUnavailable() {
        FeatureFlagClient client = new FeatureFlagClient();

        FeatureFlag flag = assertDoesNotThrow(
                () -> client.getFlag("some_unreachable_flag"),
                "getFlag must be fail-safe and never throw when feature-flag-service is unavailable");

        assertNotNull(flag, "fail-safe flag must not be null");
        assertNotNull(flag.enabled(), "fail-safe flag must have a non-null 'enabled' value");
        assertFalse(flag.enabled(), "fail-safe default must disable the flag (fail closed)");
    }

    @Test
    void failSafeFlagCarriesRequestedId() {
        FeatureFlagClient client = new FeatureFlagClient();
        FeatureFlag flag = client.getFlag("bitcoin_payments_enabled");
        assertEquals("bitcoin_payments_enabled", flag.id());
    }
}
