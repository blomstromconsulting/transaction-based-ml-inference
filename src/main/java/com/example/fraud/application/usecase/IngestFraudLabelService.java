package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.ConfirmedFraudLabel;
import com.example.fraud.domain.model.TransactionNotFoundException;
import com.example.fraud.domain.port.in.IngestFraudLabelUseCase;
import com.example.fraud.domain.port.out.FraudLabelEventPublisherPort;
import com.example.fraud.domain.port.out.FraudLabelRepositoryPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IngestFraudLabelService implements IngestFraudLabelUseCase {
    private final FraudLabelRepositoryPort labelRepositoryPort;
    private final FraudLabelEventPublisherPort eventPublisherPort;

    public IngestFraudLabelService(
            FraudLabelRepositoryPort labelRepositoryPort,
            FraudLabelEventPublisherPort eventPublisherPort) {
        this.labelRepositoryPort = labelRepositoryPort;
        this.eventPublisherPort = eventPublisherPort;
    }

    @Override
    public ConfirmedFraudLabel ingest(ConfirmedFraudLabel label) {
        if (!labelRepositoryPort.transactionExists(label.transactionId())) {
            throw new TransactionNotFoundException(label.transactionId());
        }
        labelRepositoryPort.upsertLabel(label);
        eventPublisherPort.publishLabelIngested(label);
        return label;
    }
}
