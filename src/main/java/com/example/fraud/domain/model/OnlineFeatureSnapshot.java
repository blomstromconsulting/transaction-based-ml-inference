package com.example.fraud.domain.model;

public record OnlineFeatureSnapshot(
        CustomerFeatureRow customerFeatures,
        MerchantFeatureRow merchantFeatures) {
}
