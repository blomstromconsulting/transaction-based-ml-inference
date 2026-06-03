package com.example.fraud.adapter.out.kserve;

import com.example.fraud.domain.model.FraudDecisionType;
import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.out.FraudModelInferencePort;
import com.example.fraud.infrastructure.config.ModelFeatureServiceMapping;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KServeFraudModelInferenceAdapter implements FraudModelInferencePort {
    private final ModelFeatureServiceMapping mapping;
    private final Client client;

    public KServeFraudModelInferenceAdapter(
            ModelFeatureServiceMapping mapping,
            @ConfigProperty(name = "fraud.kserve.connect-timeout-ms", defaultValue = "2000") long connectTimeoutMs,
            @ConfigProperty(name = "fraud.kserve.read-timeout-ms", defaultValue = "5000") long readTimeoutMs) {
        this.mapping = mapping;
        this.client = ClientBuilder.newBuilder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public FraudInferenceResult infer(FraudInferenceRequest request) {
        String model = request.model();
        ModelFeatureServiceMapping.ModelConfig modelConfig = mapping.model().get(model);
        if (modelConfig == null) {
            throw new IllegalArgumentException("No KServe mapping configured for " + model);
        }

        KServeInferenceEnvelope envelope = KServeInferenceEnvelope.from(request.transactionEvent(), model, modelConfig.featureService());
        KServeInferenceResponse response = callKServe(modelConfig.kserveUrl(), envelope);
        response.validate(model);
        Map<String, Object> metadata = response.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response.metadata());
        metadata.putIfAbsent("model_version", modelConfig.modelVersion());
        metadata.putIfAbsent("feature_service", modelConfig.featureService());
        return new FraudInferenceResult(
                request.transactionEvent().transactionId(),
                model,
                modelConfig.modelVersion(),
                modelConfig.featureService(),
                response.fraudScore(),
                response.decision(),
                response.featuresUsed(),
                metadata);
    }

    private KServeInferenceResponse callKServe(URI uri, KServeInferenceEnvelope envelope) {
        try (Response response = client.target(uri)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(envelope))) {
            if (response.getStatus() >= 400) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "";
                throw new IllegalStateException("KServe inference failed with HTTP "
                        + response.getStatus() + ": " + body);
            }
            return response.readEntity(KServeInferenceResponse.class);
        }
    }

    public record KServeInferenceEnvelope(
            String model,
            String featureService,
            Map<String, Object> transaction) {
        static KServeInferenceEnvelope from(TransactionEvent event, String model, String featureService) {
            Map<String, Object> transaction = new LinkedHashMap<>();
            transaction.put("transaction_id", event.transactionId());
            transaction.put("customer_id", event.customerId());
            transaction.put("card_id", event.cardId());
            transaction.put("merchant_id", event.merchantId());
            transaction.put("merchant_category", event.merchantCategory());
            transaction.put("transaction_amount", event.amount());
            transaction.put("currency", event.currency());
            transaction.put("transaction_country", event.country());
            transaction.put("timestamp", event.timestamp().toString());
            return new KServeInferenceEnvelope(model, featureService, transaction);
        }
    }

    public record KServeInferenceResponse(
            @JsonAlias({"fraud_score", "fraudScore"})
            BigDecimal fraudScore,
            FraudDecisionType decision,
            @JsonAlias({"features_used", "featuresUsed"})
            List<String> featuresUsed,
            Map<String, Object> metadata) {
        void validate(String model) {
            Objects.requireNonNull(fraudScore, "KServe response fraudScore is required for " + model);
            Objects.requireNonNull(decision, "KServe response decision is required for " + model);
            if (featuresUsed == null || featuresUsed.isEmpty()) {
                throw new IllegalStateException("KServe response featuresUsed is required for " + model);
            }
        }
    }
}
