package com.example.fraud.adapter.out.kserve;

import com.example.fraud.domain.model.FraudDecisionType;
import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;
import com.example.fraud.domain.model.FraudModel;
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

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class KServeFraudModelInferenceAdapter implements FraudModelInferencePort {
    private final ModelFeatureServiceMapping mapping;
    private final Client client;

    public KServeFraudModelInferenceAdapter(ModelFeatureServiceMapping mapping) {
        this.mapping = mapping;
        this.client = ClientBuilder.newClient();
    }

    @Override
    public FraudInferenceResult infer(FraudInferenceRequest request) {
        FraudModel model = request.model();
        ModelFeatureServiceMapping.ModelConfig modelConfig = mapping.model().get(model);
        if (modelConfig == null) {
            throw new IllegalArgumentException("No KServe mapping configured for " + model);
        }

        KServeInferenceEnvelope envelope = KServeInferenceEnvelope.from(request.transactionEvent(), model, modelConfig.featureService());
        KServeInferenceResponse response = callKServe(modelConfig.kserveUrl(), envelope);
        return new FraudInferenceResult(
                request.transactionEvent().transactionId(),
                model,
                response.fraudScore(),
                response.decision(),
                response.featuresUsed(),
                response.metadata());
    }

    private KServeInferenceResponse callKServe(URI uri, KServeInferenceEnvelope envelope) {
        try (Response response = client.target(uri)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(envelope))) {
            if (response.getStatus() >= 400) {
                throw new IllegalStateException("KServe inference failed with HTTP " + response.getStatus());
            }
            return response.readEntity(KServeInferenceResponse.class);
        }
    }

    public record KServeInferenceEnvelope(
            String model,
            String featureService,
            Map<String, Object> transaction) {
        static KServeInferenceEnvelope from(TransactionEvent event, FraudModel model, String featureService) {
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
            return new KServeInferenceEnvelope(model.name(), featureService, transaction);
        }
    }

    public record KServeInferenceResponse(
            @JsonAlias({"fraud_score", "fraudScore"})
            BigDecimal fraudScore,
            FraudDecisionType decision,
            @JsonAlias({"features_used", "featuresUsed"})
            List<String> featuresUsed,
            Map<String, Object> metadata) {
    }
}
