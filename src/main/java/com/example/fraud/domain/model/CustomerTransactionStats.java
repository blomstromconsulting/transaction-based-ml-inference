package com.example.fraud.domain.model;

import java.math.BigDecimal;

public record CustomerTransactionStats(
        long transactionCount1h,
        long transactionCount24h,
        BigDecimal totalAmount24h,
        BigDecimal averageAmount7d,
        BigDecimal maxAmount7d,
        long distinctMerchants24h,
        long crossBorderCount7d) {
}
