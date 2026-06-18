package com.example.fraud.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeastOnlineResponseTest {
    @Test
    void flattensFeastMetadataAndParallelResultsArray() throws Exception {
        String json = """
                {
                  "metadata": {
                    "feature_names": [
                      "customer_id",
                      "merchant_id",
                      "customer_transaction_count_1h",
                      "merchant_risk_score"
                    ]
                  },
                  "results": [
                    {"values": ["cust-a"], "statuses": ["PRESENT"]},
                    {"values": ["merchant-risk-1"], "statuses": ["PRESENT"]},
                    {"values": [3], "statuses": ["PRESENT"]},
                    {"values": [0.91], "statuses": ["PRESENT"]}
                  ]
                }
                """;

        Map<String, Object> features = new ObjectMapper()
                .readValue(json, FeastOnlineResponse.class)
                .toFeatureMap();

        assertEquals("cust-a", features.get("customer_id"));
        assertEquals("merchant-risk-1", features.get("merchant_id"));
        assertEquals(3, features.get("customer_transaction_count_1h"));
        assertEquals(0.91, features.get("merchant_risk_score"));
    }
}
