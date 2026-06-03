package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.OnlineFeatureSnapshot;
import com.example.fraud.domain.model.TransactionEvent;

public interface OnlineFeatureStatePort {
    OnlineFeatureSnapshot updateAndSnapshot(TransactionEvent event);
}
