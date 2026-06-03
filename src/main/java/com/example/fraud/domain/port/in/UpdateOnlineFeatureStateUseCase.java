package com.example.fraud.domain.port.in;

import com.example.fraud.domain.model.OnlineFeatureSnapshot;
import com.example.fraud.domain.model.TransactionEvent;

public interface UpdateOnlineFeatureStateUseCase {
    OnlineFeatureSnapshot update(TransactionEvent event);
}
