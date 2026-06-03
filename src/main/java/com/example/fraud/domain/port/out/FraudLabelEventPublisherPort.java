package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.ConfirmedFraudLabel;

public interface FraudLabelEventPublisherPort {
    void publishLabelIngested(ConfirmedFraudLabel label);
}
