package com.example.fraud.domain.model;

import java.math.BigDecimal;

public record MerchantVisitFeatures(
        long currentMerchantVisitCount30d,
        BigDecimal currentMerchantVisitShare30d,
        long currentMerchantRank30d,
        long currentMerchantTopVisited30d,
        BigDecimal daysSinceFirstSeenCurrentMerchant,
        BigDecimal daysSinceLastSeenCurrentMerchant,
        long customerDistinctMerchants30d,
        long newMerchantForCustomer,
        String topVisitedMerchantId30d) {
}
