package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.FeatureFlag;
import dev.openfeature.sdk.*;

public class JavaProvider implements FeatureProvider {
    private final String name = "Java Provider";
    private FeatureFlagClient featureFlagClient = new FeatureFlagClient();

    @Override
    public Metadata getMetadata() {
        return () -> name;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        FeatureFlag flag = featureFlagClient.getFlag(key);
        // The client is fail-safe and never returns null, but guard against a null 'enabled'
        // field in the flag payload by honoring the caller's default value.
        Boolean enabled = (flag != null && flag.enabled() != null) ? flag.enabled() : defaultValue;
        return constructProviderEvaluation(enabled);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return constructProviderEvaluation(defaultValue);
    }

    private <T> ProviderEvaluation<T> constructProviderEvaluation(T value) {
        ProviderEvaluation.ProviderEvaluationBuilder<T> builder = ProviderEvaluation.builder();
        return builder
                .value(value)
                .build();
    }
}
