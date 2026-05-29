package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.MerchantFeatureRow;

public interface FeatureMaterializationPort {
    void materializeCustomerFeatures(CustomerFeatureRow row);

    void materializeMerchantFeatures(MerchantFeatureRow row);
}
