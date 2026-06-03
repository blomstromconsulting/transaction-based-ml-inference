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

    public Map<String, Object> build(
            Map<String, Object> transaction,
            Map<String, Object> onlineFeatures,
            List<String> expectedOnlineFeatures) {
        validateTransaction(transaction);
        validateOnlineFeatures(transaction, onlineFeatures, expectedOnlineFeatures);

        Map<String, Object> modelInput = new LinkedHashMap<>();
        modelInput.put("transaction_amount", transaction.get("transaction_amount"));
        modelInput.put("transaction_country", transaction.get("transaction_country"));
        modelInput.put("merchant_category", transaction.get("merchant_category"));
        expectedOnlineFeatures.forEach(name -> {
            if (ModelFeatureSchema.isModelInputOnlineFeature(name)) {
                modelInput.put(name, feature(onlineFeatures, name, 0));
            }
        });

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
                .filter(field -> isMissingTransactionField(transaction.get(field)))
                .toList();
        if (!missing.isEmpty()) {
            throw new FeatureValidationException(
                    "Missing required transaction fields for feature enrichment: " + String.join(", ", missing));
        }
    }

    private void validateOnlineFeatures(
            Map<String, Object> transaction,
            Map<String, Object> onlineFeatures,
            List<String> expectedOnlineFeatures) {
        if (!strictValidation) {
            return;
        }
        List<String> missing = expectedOnlineFeatures.stream()
                .filter(ModelFeatureSchema::isModelInputOnlineFeature)
                .filter(feature -> !onlineFeatures.containsKey(feature) || isMissingOnlineFeature(onlineFeatures.get(feature)))
                .toList();
        if (!missing.isEmpty()) {
            throw new FeatureValidationException(
                    "Missing required online features from Feast: "
                            + String.join(", ", missing)
                            + "; transaction_id=" + transaction.getOrDefault("transaction_id", "<unknown>")
                            + "; customer_id=" + transaction.getOrDefault("customer_id", "<unknown>")
                            + "; merchant_id=" + transaction.getOrDefault("merchant_id", "<unknown>"));
        }
    }

    private boolean isMissingTransactionField(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private boolean isMissingOnlineFeature(Object value) {
        return value == null;
    }
}
