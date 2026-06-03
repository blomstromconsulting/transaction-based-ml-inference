package com.example.fraud.infrastructure.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.net.URI;
import java.util.Map;

@ConfigMapping(prefix = "fraud")
public interface ModelFeatureServiceMapping {
    RedisConfig redis();

    FeastConfig feast();

    @WithName("feature-writer")
    FeatureWriterConfig featureWriter();

    FeaturesConfig features();

    @WithName("offline-store")
    OfflineStoreConfig offlineStore();

    Map<String, ModelConfig> model();

    KServeConfig kserve();

    interface RedisConfig {
        String host();

        int port();
    }

    interface FeastConfig {
        URI url();
    }

    interface FeatureWriterConfig {
        boolean enabled();

        URI url();
    }

    interface FeaturesConfig {
        @WithName("home-country")
        String homeCountry();

        @WithName("merchant-visit-window-days")
        int merchantVisitWindowDays();
    }

    interface OfflineStoreConfig {
        boolean enabled();

        @WithName("jdbc-url")
        String jdbcUrl();

        String user();

        String password();
    }

    interface ModelConfig {
        @WithName("kserve-url")
        URI kserveUrl();

        @WithName("feature-service")
        String featureService();

        @WithName("model-version")
        String modelVersion();
    }

    interface KServeConfig {
        @WithName("connect-timeout-ms")
        long connectTimeoutMs();

        @WithName("read-timeout-ms")
        long readTimeoutMs();
    }
}
