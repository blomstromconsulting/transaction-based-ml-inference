package com.example.fraud.transformer;

import java.util.Map;

public record KServeEnvelope(
        String model,
        String featureService,
        Map<String, Object> transaction) {
}
