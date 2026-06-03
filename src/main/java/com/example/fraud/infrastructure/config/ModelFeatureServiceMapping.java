package com.example.fraud.infrastructure.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.net.URI;
import java.util.Map;

@ConfigMapping(prefix = "fraud")
public interface ModelFeatureServiceMapping {
    Map<String, ModelConfig> model();

    interface ModelConfig {
        @WithName("kserve-url")
        URI kserveUrl();

        @WithName("feature-service")
        String featureService();

        @WithName("model-version")
        String modelVersion();
    }
}
