package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;
import com.example.fraud.domain.model.MerchantFeatureRow;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.in.ReceiveTransactionEventUseCase;
import com.example.fraud.domain.port.in.TriggerFraudInferenceUseCase;
import com.example.fraud.domain.port.in.UpdateCustomerTransactionStatsUseCase;
import com.example.fraud.domain.port.out.FraudDecisionPublisherPort;
import com.example.fraud.domain.port.out.FeatureMaterializationPort;
import com.example.fraud.domain.port.out.OfflineTrainingDataPort;
import com.example.fraud.domain.service.TransactionNormalizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class ReceiveTransactionEventService implements ReceiveTransactionEventUseCase {
    private final TransactionNormalizer normalizer = new TransactionNormalizer();
    private final UpdateCustomerTransactionStatsUseCase updateStatsUseCase;
    private final FeatureMaterializationPort featureMaterializationPort;
    private final OfflineTrainingDataPort offlineTrainingDataPort;
    private final TriggerFraudInferenceUseCase triggerFraudInferenceUseCase;
    private final FraudDecisionPublisherPort decisionPublisherPort;

    public ReceiveTransactionEventService(
            UpdateCustomerTransactionStatsUseCase updateStatsUseCase,
            FeatureMaterializationPort featureMaterializationPort,
            OfflineTrainingDataPort offlineTrainingDataPort,
            TriggerFraudInferenceUseCase triggerFraudInferenceUseCase,
            FraudDecisionPublisherPort decisionPublisherPort) {
        this.updateStatsUseCase = updateStatsUseCase;
        this.featureMaterializationPort = featureMaterializationPort;
        this.offlineTrainingDataPort = offlineTrainingDataPort;
        this.triggerFraudInferenceUseCase = triggerFraudInferenceUseCase;
        this.decisionPublisherPort = decisionPublisherPort;
    }

    @Override
    public FraudDecision receive(TransactionEvent event) {
        TransactionEvent normalized = normalizer.normalize(event);
        offlineTrainingDataPort.recordTransaction(normalized);

        CustomerTransactionStats stats = updateStatsUseCase.update(normalized);
        CustomerFeatureRow customerFeatureRow = CustomerFeatureRow.from(normalized, stats);
        MerchantFeatureRow merchantFeatureRow = MerchantFeatureRow.from(normalized);

        featureMaterializationPort.materializeCustomerFeatures(customerFeatureRow);
        featureMaterializationPort.materializeMerchantFeatures(merchantFeatureRow);
        offlineTrainingDataPort.recordCustomerFeatures(customerFeatureRow);
        offlineTrainingDataPort.recordMerchantFeatures(merchantFeatureRow);

        FraudInferenceResult result = triggerFraudInferenceUseCase.trigger(new FraudInferenceRequest(normalized));
        FraudDecision decision = new FraudDecision(
                normalized.transactionId(),
                normalized.customerId(),
                normalized.requestedModel(),
                result.decision(),
                result,
                Instant.now());
        decisionPublisherPort.publish(decision);
        offlineTrainingDataPort.recordPrediction(decision);
        return decision;
    }
}
