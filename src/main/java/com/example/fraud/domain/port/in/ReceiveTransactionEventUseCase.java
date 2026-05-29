package com.example.fraud.domain.port.in;

import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.TransactionEvent;

public interface ReceiveTransactionEventUseCase {
    FraudDecision receive(TransactionEvent event);
}
