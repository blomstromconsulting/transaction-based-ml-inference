package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.FraudInferenceRequest;
import com.example.fraud.domain.model.FraudInferenceResult;
import com.example.fraud.domain.port.in.TriggerFraudInferenceUseCase;
import com.example.fraud.domain.port.out.FraudModelInferencePort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TriggerFraudInferenceService implements TriggerFraudInferenceUseCase {
    private final FraudModelInferencePort fraudModelInferencePort;

    public TriggerFraudInferenceService(FraudModelInferencePort fraudModelInferencePort) {
        this.fraudModelInferencePort = fraudModelInferencePort;
    }

    @Override
    public FraudInferenceResult trigger(FraudInferenceRequest request) {
        return fraudModelInferencePort.infer(request);
    }
}
