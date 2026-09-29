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

public class FeatureFlagClient {
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagClient.class);

    // Bounded timeouts so a slow/unresponsive feature-flag-service sheds load instead of
    // tying up request threads. feature-flag-service is the highest-throughput dependency
    // and the first monitored tier likely to saturate under a large traffic increase, so an
    // unbounded client here is a cascading-failure risk. Tunable via HTTP_CLIENT_TIMEOUT_SECONDS.
    private static final Duration TIMEOUT = Duration.ofSeconds(readTimeoutSeconds());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String featureFlagServiceUrl = System.getenv("FEATURE_FLAG_SERVICE_PROTOCOL") + "://"
            + System.getenv("FEATURE_FLAG_SERVICE_BASE_URL") + ":" + System.getenv("FEATURE_FLAG_SERVICE_PORT")
            + "/v1/flags/";

    private static long readTimeoutSeconds() {
        String value = System.getenv("HTTP_CLIENT_TIMEOUT_SECONDS");
        if (value == null || value.isBlank()) {
            return 5L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid HTTP_CLIENT_TIMEOUT_SECONDS ('{}'), defaulting to 5", value);
            return 5L;
        }
    }

    public FeatureFlag getFlag(String flagId) {
        logger.info("Getting feature flag with id: {}", flagId);

        // deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(featureFlagServiceUrl + flagId))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readValue(response.body(), FeatureFlag.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
