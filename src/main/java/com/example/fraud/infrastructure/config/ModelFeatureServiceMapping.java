package com.example.fraud.infrastructure.config;

import com.example.fraud.domain.model.FraudModel;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.net.URI;
import java.util.Map;

@ConfigMapping(prefix = "fraud")
public interface ModelFeatureServiceMapping {
    Map<FraudModel, ModelConfig> model();

    interface ModelConfig {
        @WithName("kserve-url")
        URI kserveUrl();

        @WithName("feature-service")
        String featureService();
    }
}
