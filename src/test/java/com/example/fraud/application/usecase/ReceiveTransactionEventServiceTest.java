package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.FraudDecisionType;
import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.in.TriggerFraudInferenceUseCase;
import com.example.fraud.domain.port.in.UpdateCustomerTransactionStatsUseCase;
import com.example.fraud.domain.port.out.FraudDecisionPublisherPort;
import com.example.fraud.domain.port.out.FeatureMaterializationPort;
import com.example.fraud.domain.port.out.OfflineDataSinkPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiveTransactionEventServiceTest {
    @Test
    void updatesStatsTriggersInferenceAndPublishesDecision() {
        AtomicBoolean statsUpdated = new AtomicBoolean(false);
        AtomicBoolean featuresMaterialized = new AtomicBoolean(false);
        AtomicBoolean offlineDataWritten = new AtomicBoolean(false);
        AtomicBoolean published = new AtomicBoolean(false);

        UpdateCustomerTransactionStatsUseCase statsUseCase = event -> {
            statsUpdated.set(true);
            return new CustomerTransactionStats(1, 1, event.amount(), event.amount(), event.amount(), 1, 0);
        };
        TriggerFraudInferenceUseCase inferenceUseCase = request -> result(request);
        FeatureMaterializationPort featureMaterializationPort = new FeatureMaterializationPort() {
            @Override
            public void materializeCustomerFeatures(com.example.fraud.domain.model.CustomerFeatureRow row) {
                featuresMaterialized.set(true);
            }

            @Override
            public void materializeMerchantFeatures(com.example.fraud.domain.model.MerchantFeatureRow row) {
                featuresMaterialized.set(true);
            }
        };
        OfflineDataSinkPort offlineDataSinkPort = new OfflineDataSinkPort() {
            @Override
            public void recordTransaction(TransactionEvent event) {
                offlineDataWritten.set(true);
            }

            @Override
            public void recordCustomerFeatures(com.example.fraud.domain.model.CustomerFeatureRow row) {
                offlineDataWritten.set(true);
            }

            @Override
            public void recordMerchantFeatures(com.example.fraud.domain.model.MerchantFeatureRow row) {
                offlineDataWritten.set(true);
            }

            @Override
            public void recordPrediction(FraudDecision decision) {
                offlineDataWritten.set(true);
            }
        };
        FraudDecisionPublisherPort publisher = decision -> published.set(true);

        ReceiveTransactionEventService service = new ReceiveTransactionEventService(
                statsUseCase,
                featureMaterializationPort,
                offlineDataSinkPort,
                inferenceUseCase,
                publisher);
        FraudDecision decision = service.receive(event());

        assertTrue(statsUpdated.get());
        assertTrue(featuresMaterialized.get());
        assertTrue(offlineDataWritten.get());
        assertTrue(published.get());
        assertEquals("tx-10001", decision.transactionId());
        assertEquals(FraudDecisionType.DECLINE, decision.decision());
        assertEquals("MODEL_B", decision.model());
    }

    private FraudInferenceResult result(FraudInferenceRequest request) {
        return new FraudInferenceResult(
                request.transactionEvent().transactionId(),
                request.model(),
                "test-version",
                "test_feature_service",
                new BigDecimal("0.91"),
                FraudDecisionType.DECLINE,
                List.of("transaction_amount", "customer_transaction_count_24h"),
                Map.of("threshold", "0.80"));
    }

    private TransactionEvent event() {
        return new TransactionEvent(
                "tx-10001",
                "cust-123",
                "card-456",
                "merchant-789",
                "electronics",
                new BigDecimal("1299.99"),
                "eur",
                "se",
                Instant.parse("2026-05-29T12:00:00Z"),
                "MODEL_B");
    }
}
