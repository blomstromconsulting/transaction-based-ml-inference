package com.example.fraud.adapter.out.event;

import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.port.out.FraudDecisionPublisherPort;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FraudDecisionPublisherAdapter implements FraudDecisionPublisherPort {
    @Override
    public void publish(FraudDecision decision) {
        Log.infof("fraud_decision_event transaction_id=%s customer_id=%s model=%s decision=%s score=%s",
                decision.transactionId(),
                decision.customerId(),
                decision.model(),
                decision.decision(),
                decision.inferenceResult().fraudScore());
    }
}
