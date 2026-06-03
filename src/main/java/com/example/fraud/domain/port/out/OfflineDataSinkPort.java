package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.MerchantFeatureRow;
import com.example.fraud.domain.model.TransactionEvent;

public interface OfflineDataSinkPort {
    default boolean recordProcessingStarted(TransactionEvent event) {
        return true;
    }

    default void recordProcessingFailure(TransactionEvent event, RuntimeException failure) {
    }

    void recordTransaction(TransactionEvent event);

    void recordCustomerFeatures(CustomerFeatureRow row);

    void recordMerchantFeatures(MerchantFeatureRow row);

    void recordPrediction(FraudDecision decision);

    default void recordDecisionPublished(FraudDecision decision) {
    }
}
