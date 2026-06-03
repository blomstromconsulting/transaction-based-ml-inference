package com.example.fraud.transformer;

import java.util.List;
import java.util.Map;

public record PredictorRequest(
        List<Map<String, Object>> instances,
        Map<String, Object> parameters) {
}
