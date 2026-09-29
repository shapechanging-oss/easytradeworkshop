package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A persisted bitcoin payment. Mirrors a row of the {@code [dbo].[BitcoinPayments]} table.
 */
public record BitcoinPayment(
        String id,
        Integer accountId,
        String idempotencyKey,
        BigDecimal fiatAmount,
        String fiatCurrency,
        BigDecimal amountBtc,
        String btcAddress,
        String status,
        OffsetDateTime createdAt) {

    public static BitcoinPayment fromResultSet(ResultSet rs) throws SQLException {
        return new BitcoinPayment(
                rs.getString("Id"),
                rs.getInt("AccountId"),
                rs.getString("IdempotencyKey"),
                rs.getBigDecimal("FiatAmount"),
                rs.getString("FiatCurrency"),
                rs.getBigDecimal("AmountBtc"),
                rs.getString("BtcAddress"),
                rs.getString("Status"),
                rs.getTimestamp("CreatedAt").toInstant().atOffset(ZoneOffset.UTC));
    }
}
