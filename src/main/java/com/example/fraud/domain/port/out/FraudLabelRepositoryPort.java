package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.ConfirmedFraudLabel;

public interface FraudLabelRepositoryPort {
    boolean transactionExists(String transactionId);

    void upsertLabel(ConfirmedFraudLabel label);
}
