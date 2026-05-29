package com.example.fraud.domain.port.in;

import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;

public interface TriggerFraudInferenceUseCase {
    FraudInferenceResult trigger(FraudInferenceRequest request);
}
