package com.example.fraud.transformer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record FraudResponse(
        BigDecimal fraudScore,
        String decision,
        List<String> featuresUsed,
        Map<String, Object> metadata) {
}
