package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;

public interface FraudModelInferencePort {
    FraudInferenceResult infer(FraudInferenceRequest request);
}
