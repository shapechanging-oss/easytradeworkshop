package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.FeatureFlag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public class FeatureFlagClient {
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagClient.class);

    // Bounded timeouts so a slow/unresponsive feature-flag-service sheds load instead of
    // tying up request threads. feature-flag-service is the highest-throughput dependency
    // and the first monitored tier likely to saturate under a large traffic increase, so an
    // unbounded client here is a cascading-failure risk. Tunable via HTTP_CLIENT_TIMEOUT_SECONDS.
    private static final Duration TIMEOUT = Duration.ofSeconds(readLongEnv("HTTP_CLIENT_TIMEOUT_SECONDS", 5L));

    // Short-TTL cache in front of the feature-flag lookup. The service evaluates a flag on
    // essentially every request, so at high traffic this is the dominant per-request network
    // fan-out to feature-flag-service (the busiest dependency). Caching for a few seconds
    // collapses that to at most one network call per flag per TTL, while a flag stays fresh
    // enough for rollout/kill-switch use (flags change on the order of minutes, not millis).
    // Tunable via FEATURE_FLAG_CACHE_TTL_SECONDS; set 0 to disable caching.
    private static final long CACHE_TTL_MILLIS = readLongEnv("FEATURE_FLAG_CACHE_TTL_SECONDS", 5L) * 1000L;
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String featureFlagServiceUrl = System.getenv("FEATURE_FLAG_SERVICE_PROTOCOL") + "://"
            + System.getenv("FEATURE_FLAG_SERVICE_BASE_URL") + ":" + System.getenv("FEATURE_FLAG_SERVICE_PORT")
            + "/v1/flags/";

    private record CacheEntry(FeatureFlag flag, long expiresAtMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    private static long readLongEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid value for {} ('{}'), defaulting to {}", name, value, defaultValue);
            return defaultValue;
        }
    }

    public FeatureFlag getFlag(String flagId) {
        // Serve a fresh cached value without a network call.
        if (CACHE_TTL_MILLIS > 0) {
            CacheEntry cached = CACHE.get(flagId);
            if (cached != null && cached.isFresh()) {
                return cached.flag();
            }
        }

        try {
            FeatureFlag flag = fetchFlag(flagId);
            if (CACHE_TTL_MILLIS > 0) {
                CACHE.put(flagId, new CacheEntry(flag, System.currentTimeMillis() + CACHE_TTL_MILLIS));
            }
            return flag;
        } catch (RuntimeException e) {
            // Fail safe: never let a flag-service outage break the caller. Prefer a stale
            // cached value (even if expired) so behaviour is stable during a blip; otherwise
            // fall back to a disabled flag. This keeps payments serving instead of 500-ing
            // when feature-flag-service is slow or down.
            CacheEntry stale = CACHE.get(flagId);
            if (stale != null) {
                logger.warn("feature-flag-service unavailable for '{}' ({}); serving last known value",
                        flagId, e.getMessage());
                return stale.flag();
            }
            logger.warn("feature-flag-service unavailable for '{}' ({}); defaulting flag to disabled",
                    flagId, e.getMessage());
            return new FeatureFlag(flagId, false, flagId, "unavailable-default", false, null);
        }
    }

    private FeatureFlag fetchFlag(String flagId) {
        logger.debug("Getting feature flag with id: {}", flagId);

        // deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(featureFlagServiceUrl + flagId))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readValue(response.body(), FeatureFlag.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
