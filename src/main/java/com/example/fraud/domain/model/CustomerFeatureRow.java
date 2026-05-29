package com.example.fraud.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerFeatureRow(
        String customerId,
        Instant eventTimestamp,
        long customerTransactionCount1h,
        long customerTransactionCount24h,
        BigDecimal customerTotalAmount24h,
        BigDecimal customerAvgAmount7d,
        BigDecimal customerMaxAmount7d,
        long customerDistinctMerchants24h,
        long customerCrossBorderCount7d) {

    public static CustomerFeatureRow from(TransactionEvent event, CustomerTransactionStats stats) {
        return new CustomerFeatureRow(
                event.customerId(),
                event.timestamp(),
                stats.transactionCount1h(),
                stats.transactionCount24h(),
                stats.totalAmount24h(),
                stats.averageAmount7d(),
                stats.maxAmount7d(),
                stats.distinctMerchants24h(),
                stats.crossBorderCount7d());
    }
}
