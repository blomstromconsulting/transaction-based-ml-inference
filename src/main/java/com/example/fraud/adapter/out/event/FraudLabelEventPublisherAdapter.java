package com.example.fraud.adapter.out.event;

import com.example.fraud.domain.model.ConfirmedFraudLabel;
import com.example.fraud.domain.port.out.FraudLabelEventPublisherPort;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FraudLabelEventPublisherAdapter implements FraudLabelEventPublisherPort {
    @Override
    public void publishLabelIngested(ConfirmedFraudLabel label) {
        Log.infof("fraud_label_ingested transaction_id=%s is_fraud=%s label_source=%s confidence=%s",
                label.transactionId(),
                label.fraud(),
                label.labelSource(),
                label.labelConfidence());
    }
}
