package com.example.fraud.domain.port.in;

import com.example.fraud.domain.model.ConfirmedFraudLabel;

public interface IngestFraudLabelUseCase {
    ConfirmedFraudLabel ingest(ConfirmedFraudLabel label);
}
