package com.example.fraud.transformer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeastOnlineResponse(Metadata metadata, List<Result> results) {
    public Map<String, Object> toFeatureMap() {
        Map<String, Object> flattened = new LinkedHashMap<>();
        if (metadata == null || metadata.featureNames == null || results == null) {
            return flattened;
        }
        for (int index = 0; index < metadata.featureNames.size() && index < results.size(); index++) {
            Result result = results.get(index);
            Object value = result.values == null || result.values.isEmpty() ? null : result.values.get(0);
            flattened.put(metadata.featureNames.get(index), value);
        }
        return flattened;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(@JsonProperty("feature_names") List<String> featureNames) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Object> values) {
    }
}
