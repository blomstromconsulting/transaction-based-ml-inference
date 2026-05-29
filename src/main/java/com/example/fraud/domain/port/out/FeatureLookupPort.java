package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.TransactionEvent;

import java.util.Map;

public interface FeatureLookupPort {
    Map<String, Object> lookupOnlineFeatures(String featureServiceName, TransactionEvent event);
}
