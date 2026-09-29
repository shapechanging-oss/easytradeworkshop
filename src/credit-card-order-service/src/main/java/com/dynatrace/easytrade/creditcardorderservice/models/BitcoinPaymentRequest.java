package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Request to initiate a bitcoin payment.
 *
 * <p>{@code idempotencyKey} is a client-supplied unique reference for the payment. Retries
 * carrying the same key are safe: the server returns the existing payment rather than
 * creating a duplicate. This is enforced by a unique index on the BitcoinPayments table
 * and matters under high load / at-least-once upstreams, where the same request may arrive
 * more than once.
 */
public record BitcoinPaymentRequest(
        Integer accountId,
        String idempotencyKey,
        BigDecimal fiatAmount,
        String fiatCurrency,
        String btcAddress) {
    public BitcoinPaymentRequest {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(fiatAmount, "fiatAmount is required");
        Objects.requireNonNull(fiatCurrency, "fiatCurrency is required");
        Objects.requireNonNull(btcAddress, "btcAddress is required");
    }
}
