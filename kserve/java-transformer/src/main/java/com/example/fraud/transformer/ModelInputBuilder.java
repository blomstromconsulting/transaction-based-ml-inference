package com.example.fraud.transformer;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ModelInputBuilder {
    private final boolean strictValidation;

    public ModelInputBuilder(
            @ConfigProperty(name = "fraud.transformer.strict-feature-validation", defaultValue = "true")
            boolean strictValidation) {
        this.strictValidation = strictValidation;
    }

    public Map<String, Object> build(FraudModel model, Map<String, Object> transaction, Map<String, Object> onlineFeatures) {
        validateTransaction(transaction);
        validateOnlineFeatures(model, transaction, onlineFeatures);

        Map<String, Object> modelInput = new LinkedHashMap<>();
        modelInput.put("transaction_amount", transaction.get("transaction_amount"));
        modelInput.put("transaction_country", transaction.get("transaction_country"));
        modelInput.put("merchant_category", transaction.get("merchant_category"));
        modelInput.put("customer_transaction_count_1h", feature(onlineFeatures, "customer_transaction_count_1h", 0));
        modelInput.put("customer_transaction_count_24h", feature(onlineFeatures, "customer_transaction_count_24h", 0));
        modelInput.put("customer_total_amount_24h", feature(onlineFeatures, "customer_total_amount_24h", 0.0));
        modelInput.put("customer_avg_amount_7d", feature(onlineFeatures, "customer_avg_amount_7d", 0.0));

        if (model == FraudModel.MODEL_B) {
            modelInput.put("customer_max_amount_7d", feature(onlineFeatures, "customer_max_amount_7d", 0.0));
            modelInput.put("customer_distinct_merchants_24h", feature(onlineFeatures, "customer_distinct_merchants_24h", 0));
            modelInput.put("customer_cross_border_count_7d", feature(onlineFeatures, "customer_cross_border_count_7d", 0));
            modelInput.put("merchant_risk_score", feature(onlineFeatures, "merchant_risk_score", 0.0));
        }

        return modelInput;
    }

    private Object feature(Map<String, Object> onlineFeatures, String name, Object defaultValue) {
        if (strictValidation) {
            return onlineFeatures.get(name);
        }
        return onlineFeatures.getOrDefault(name, defaultValue);
    }

    private void validateTransaction(Map<String, Object> transaction) {
        if (!strictValidation) {
            return;
        }
        List<String> missing = ModelFeatureSchema.requiredTransactionFields().stream()
                .filter(field -> isMissing(transaction.get(field)))
                .toList();
        if (!missing.isEmpty()) {
            throw new FeatureValidationException(
                    "Missing required transaction fields for feature enrichment: " + String.join(", ", missing));
        }
    }

    private void validateOnlineFeatures(FraudModel model, Map<String, Object> transaction, Map<String, Object> onlineFeatures) {
        if (!strictValidation) {
            return;
        }
        List<String> missing = ModelFeatureSchema.requiredOnlineFeatures(model).stream()
                .filter(feature -> isMissing(onlineFeatures.get(feature)))
                .toList();
        if (!missing.isEmpty()) {
            throw new FeatureValidationException(
                    "Missing required online features from Feast: " + String.join(", ", missing)
                            + "; transaction_id=" + transaction.getOrDefault("transaction_id", "<unknown>")
                            + "; customer_id=" + transaction.getOrDefault("customer_id", "<unknown>")
                            + "; merchant_id=" + transaction.getOrDefault("merchant_id", "<unknown>"));
        }
    }

    private boolean isMissing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }
}
