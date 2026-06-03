package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;
import com.example.fraud.domain.model.OnlineFeatureSnapshot;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.model.TransactionAlreadyProcessedException;
import com.example.fraud.domain.port.in.ReceiveTransactionEventUseCase;
import com.example.fraud.domain.port.in.TriggerFraudInferenceUseCase;
import com.example.fraud.domain.port.in.UpdateOnlineFeatureStateUseCase;
import com.example.fraud.domain.port.out.FraudDecisionPublisherPort;
import com.example.fraud.domain.port.out.FeatureMaterializationPort;
import com.example.fraud.domain.port.out.OfflineDataSinkPort;
import com.example.fraud.domain.service.TransactionNormalizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class ReceiveTransactionEventService implements ReceiveTransactionEventUseCase {
    private final TransactionNormalizer normalizer = new TransactionNormalizer();
    private final UpdateOnlineFeatureStateUseCase updateFeatureStateUseCase;
    private final FeatureMaterializationPort featureMaterializationPort;
    private final OfflineDataSinkPort offlineDataSinkPort;
    private final TriggerFraudInferenceUseCase triggerFraudInferenceUseCase;
    private final FraudDecisionPublisherPort decisionPublisherPort;

    public ReceiveTransactionEventService(
            UpdateOnlineFeatureStateUseCase updateFeatureStateUseCase,
            FeatureMaterializationPort featureMaterializationPort,
            OfflineDataSinkPort offlineDataSinkPort,
            TriggerFraudInferenceUseCase triggerFraudInferenceUseCase,
            FraudDecisionPublisherPort decisionPublisherPort) {
        this.updateFeatureStateUseCase = updateFeatureStateUseCase;
        this.featureMaterializationPort = featureMaterializationPort;
        this.offlineDataSinkPort = offlineDataSinkPort;
        this.triggerFraudInferenceUseCase = triggerFraudInferenceUseCase;
        this.decisionPublisherPort = decisionPublisherPort;
    }

    @Override
    public FraudDecision receive(TransactionEvent event) {
        TransactionEvent normalized = normalizer.normalize(event);
        if (!offlineDataSinkPort.recordProcessingStarted(normalized)) {
            throw new TransactionAlreadyProcessedException(normalized.transactionId());
        }

        try {
            return process(normalized);
        } catch (RuntimeException e) {
            recordFailure(normalized, e);
            throw e;
        }
    }

    private FraudDecision process(TransactionEvent normalized) {
        offlineDataSinkPort.recordTransaction(normalized);

        OnlineFeatureSnapshot featureSnapshot = updateFeatureStateUseCase.update(normalized);

        featureMaterializationPort.materializeCustomerFeatures(featureSnapshot.customerFeatures());
        featureMaterializationPort.materializeMerchantFeatures(featureSnapshot.merchantFeatures());
        offlineDataSinkPort.recordCustomerFeatures(featureSnapshot.customerFeatures());
        offlineDataSinkPort.recordMerchantFeatures(featureSnapshot.merchantFeatures());

        FraudInferenceResult result = triggerFraudInferenceUseCase.trigger(new FraudInferenceRequest(normalized));
        FraudDecision decision = new FraudDecision(
                normalized.transactionId(),
                normalized.customerId(),
                normalized.requestedModel(),
                result.modelVersion(),
                result.decision(),
                result,
                Instant.now());
        offlineDataSinkPort.recordPrediction(decision);
        decisionPublisherPort.publish(decision);
        offlineDataSinkPort.recordDecisionPublished(decision);
        return decision;
    }

    private void recordFailure(TransactionEvent normalized, RuntimeException failure) {
        try {
            offlineDataSinkPort.recordProcessingFailure(normalized, failure);
        } catch (RuntimeException ignored) {
            // Preserve the original workflow failure for callers.
        }
    }
}
