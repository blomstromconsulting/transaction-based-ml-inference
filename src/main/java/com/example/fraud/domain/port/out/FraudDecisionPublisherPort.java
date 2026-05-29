package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.FraudDecision;

public interface FraudDecisionPublisherPort {
    void publish(FraudDecision decision);
}
