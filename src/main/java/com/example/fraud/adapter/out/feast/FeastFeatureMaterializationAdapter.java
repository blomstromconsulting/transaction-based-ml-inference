package com.example.fraud.adapter.out.feast;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.MerchantFeatureRow;
import com.example.fraud.domain.port.out.FeatureMaterializationPort;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class FeastFeatureMaterializationAdapter implements FeatureMaterializationPort {
    private final FeatureWriterClient client;
    private final boolean enabled;

    public FeastFeatureMaterializationAdapter(
            @RestClient FeatureWriterClient client,
            @ConfigProperty(name = "fraud.feature-writer.enabled", defaultValue = "false") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    @Override
    public void materializeCustomerFeatures(CustomerFeatureRow row) {
        if (!enabled) {
            Log.debugf("Feature writer disabled; skipping customer feature materialization for %s", row.customerId());
            return;
        }
        client.materializeCustomer(CustomerFeatureMaterializationRequest.from(row));
    }

    @Override
    public void materializeMerchantFeatures(MerchantFeatureRow row) {
        if (!enabled) {
            Log.debugf("Feature writer disabled; skipping merchant feature materialization for %s", row.merchantId());
            return;
        }
        client.materializeMerchant(MerchantFeatureMaterializationRequest.from(row));
    }
}
