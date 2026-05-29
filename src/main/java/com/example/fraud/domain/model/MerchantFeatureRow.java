package com.example.fraud.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MerchantFeatureRow(
        String merchantId,
        Instant eventTimestamp,
        BigDecimal merchantRiskScore,
        String merchantCategory) {

    public static MerchantFeatureRow from(TransactionEvent event) {
        return new MerchantFeatureRow(
                event.merchantId(),
                event.timestamp(),
                new BigDecimal("0.50"),
                event.merchantCategory());
    }
}
