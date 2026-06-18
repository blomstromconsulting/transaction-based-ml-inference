package com.example.fraud.transformer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Path("/")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class FraudJavaTransformerResource {
    private final URI feastUrl;
    private final URI predictorUrl;
    private final String targetModel;
    private final String modelAFeatureService;
    private final String modelBFeatureService;
    private final ModelInputBuilder modelInputBuilder;
    private final Client client;

    public FraudJavaTransformerResource(
            @ConfigProperty(name = "fraud.feast.url") URI feastUrl,
            @ConfigProperty(name = "fraud.predictor.url") URI predictorUrl,
            @ConfigProperty(name = "fraud.transformer.target-model", defaultValue = "MODEL_A") String targetModel,
            @ConfigProperty(name = "fraud.model.MODEL_A.feature-service") String modelAFeatureService,
            @ConfigProperty(name = "fraud.model.MODEL_B.feature-service") String modelBFeatureService,
            ModelInputBuilder modelInputBuilder) {
        this.feastUrl = feastUrl;
        this.predictorUrl = predictorUrl;
        this.targetModel = targetModel;
        this.modelAFeatureService = modelAFeatureService;
        this.modelBFeatureService = modelBFeatureService;
        this.modelInputBuilder = modelInputBuilder;
        this.client = ClientBuilder.newClient();
    }

    @POST
    @Path("/v1/models/{modelName}:predict")
    public FraudResponse predict(@PathParam("modelName") String modelName, KServeEnvelope envelope) {
        String model = resolveModel(envelope);
        String featureService = resolveFeatureService(model, envelope);
        Map<String, Object> transaction = Objects.requireNonNull(envelope.transaction(), "transaction is required");

        Map<String, Object> onlineFeatures = lookupFeatures(featureService, transaction);
        Map<String, Object> modelInput = modelInputBuilder.build(
                transaction,
                onlineFeatures,
                ModelFeatureSchema.onlineFeaturesForModel(model));
        PredictorRequest predictorRequest = new PredictorRequest(
                List.of(modelInput),
                Map.of(
                        "transaction_id", transaction.get("transaction_id"),
                        "model", model,
                        "feature_service", featureService,
                        "features_used", List.copyOf(modelInput.keySet())));

        Map<String, Object> predictorResponse = callPredictor(predictorRequest);
        BigDecimal score = fraudScore(predictorResponse);
        return new FraudResponse(
                score,
                decision(score),
                List.copyOf(modelInput.keySet()),
                Map.of(
                        "model", model,
                        "feature_service", featureService,
                        "source", "java-kserve-transformer-feast"));
    }

    private String resolveModel(KServeEnvelope envelope) {
        String modelName = envelope.model() == null || envelope.model().isBlank()
                ? targetModel
                : envelope.model().trim();
        return modelName.toUpperCase();
    }

    private String resolveFeatureService(String model, KServeEnvelope envelope) {
        if (envelope.featureService() != null && !envelope.featureService().isBlank()) {
            return envelope.featureService();
        }
        return "MODEL_A".equals(model) ? modelAFeatureService : modelBFeatureService;
    }

    private Map<String, Object> lookupFeatures(String featureService, Map<String, Object> transaction) {
        FeastOnlineRequest request = new FeastOnlineRequest(
                featureService,
                Map.of(
                        "customer_id", List.of(value(transaction, "customer_id")),
                        "merchant_id", List.of(value(transaction, "merchant_id"))));
        try (Response response = client.target(feastUrl)
                .path("/get-online-features")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(request))) {
            if (response.getStatus() >= 400) {
                throw new IllegalStateException("Feast online feature lookup failed with HTTP " + response.getStatus());
            }
            FeastOnlineResponse feastResponse = response.readEntity(FeastOnlineResponse.class);
            return feastResponse.toFeatureMap();
        }
    }

    private String value(Map<String, Object> transaction, String key) {
        Object value = transaction.get(key);
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPredictor(PredictorRequest request) {
        try (Response response = client.target(predictorUrl)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(request))) {
            if (response.getStatus() >= 400) {
                throw new IllegalStateException("Predictor call failed with HTTP " + response.getStatus());
            }
            return response.readEntity(Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal fraudScore(Map<String, Object> predictorResponse) {
        Object predictions = predictorResponse.get("predictions");
        if (!(predictions instanceof List<?> values) || values.isEmpty() || !(values.get(0) instanceof Map<?, ?> first)) {
            return BigDecimal.ZERO;
        }
        Object score = ((Map<String, Object>) first).getOrDefault("fraud_score", first.get("score"));
        if (score instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (score instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return BigDecimal.ZERO;
    }

    private String decision(BigDecimal score) {
        if (score.compareTo(new BigDecimal("0.85")) >= 0) {
            return "DECLINE";
        }
        if (score.compareTo(new BigDecimal("0.60")) >= 0) {
            return "REVIEW";
        }
        return "APPROVE";
    }
}
