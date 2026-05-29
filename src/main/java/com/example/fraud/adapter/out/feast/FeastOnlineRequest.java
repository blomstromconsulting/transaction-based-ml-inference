package com.example.fraud.adapter.out.feast;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record FeastOnlineRequest(
        @JsonProperty("feature_service") String featureService,
        Map<String, List<String>> entities) {
}
