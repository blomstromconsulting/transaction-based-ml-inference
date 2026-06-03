package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.OnlineFeatureSnapshot;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.in.UpdateOnlineFeatureStateUseCase;
import com.example.fraud.domain.port.out.OnlineFeatureStatePort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UpdateOnlineFeatureStateService implements UpdateOnlineFeatureStateUseCase {
    private final OnlineFeatureStatePort featureStatePort;

    public UpdateOnlineFeatureStateService(OnlineFeatureStatePort featureStatePort) {
        this.featureStatePort = featureStatePort;
    }

    @Override
    public OnlineFeatureSnapshot update(TransactionEvent event) {
        return featureStatePort.updateAndSnapshot(event);
    }
}
