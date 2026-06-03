package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.ConfirmedFraudLabel;
import com.example.fraud.domain.model.TransactionNotFoundException;
import com.example.fraud.domain.port.out.FraudLabelEventPublisherPort;
import com.example.fraud.domain.port.out.FraudLabelRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestFraudLabelServiceTest {
    @Test
    void upsertsLabelAndPublishesEventForKnownTransaction() {
        AtomicBoolean upserted = new AtomicBoolean(false);
        AtomicBoolean published = new AtomicBoolean(false);
        FraudLabelRepositoryPort repository = new FraudLabelRepositoryPort() {
            @Override
            public boolean transactionExists(String transactionId) {
                return true;
            }

            @Override
            public void upsertLabel(ConfirmedFraudLabel label) {
                upserted.set(true);
            }
        };
        FraudLabelEventPublisherPort publisher = label -> published.set(true);
        IngestFraudLabelService service = new IngestFraudLabelService(repository, publisher);

        ConfirmedFraudLabel ingested = service.ingest(label());

        assertEquals("tx-10001", ingested.transactionId());
        assertTrue(upserted.get());
        assertTrue(published.get());
    }

    @Test
    void rejectsUnknownTransaction() {
        FraudLabelRepositoryPort repository = new FraudLabelRepositoryPort() {
            @Override
            public boolean transactionExists(String transactionId) {
                return false;
            }

            @Override
            public void upsertLabel(ConfirmedFraudLabel label) {
            }
        };
        IngestFraudLabelService service = new IngestFraudLabelService(repository, label -> {
        });

        assertThrows(TransactionNotFoundException.class, () -> service.ingest(label()));
    }

    @Test
    void rejectsInvalidConfidence() {
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedFraudLabel(
                "tx-10001",
                true,
                Instant.parse("2026-06-03T08:30:00Z"),
                "chargeback",
                new BigDecimal("1.1"),
                "chargeback-system",
                "confirmed_dispute"));
    }

    private ConfirmedFraudLabel label() {
        return new ConfirmedFraudLabel(
                "tx-10001",
                true,
                Instant.parse("2026-06-03T08:30:00Z"),
                "Chargeback",
                BigDecimal.ONE,
                "chargeback-system",
                "confirmed_dispute");
    }
}
