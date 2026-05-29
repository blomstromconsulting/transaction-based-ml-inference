package com.example.fraud.adapter.out.feast;

import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.out.FeatureLookupPort;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class FeastFeatureLookupAdapter implements FeatureLookupPort {
    private final FeastFeatureServerClient client;

    public FeastFeatureLookupAdapter(@RestClient FeastFeatureServerClient client) {
        this.client = client;
    }

    @Override
    public Map<String, Object> lookupOnlineFeatures(String featureServiceName, TransactionEvent event) {
        FeastOnlineRequest request = new FeastOnlineRequest(
                featureServiceName,
                Map.of(
                        "customer_id", List.of(event.customerId()),
                        "merchant_id", List.of(event.merchantId())));
        return client.getOnlineFeatures(request).results();
    }
}
