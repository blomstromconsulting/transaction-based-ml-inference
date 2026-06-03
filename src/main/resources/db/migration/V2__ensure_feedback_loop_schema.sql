CREATE TABLE IF NOT EXISTS fraud_labels (
    transaction_id TEXT PRIMARY KEY REFERENCES fraud_transactions(transaction_id),
    is_fraud INTEGER NOT NULL CHECK (is_fraud IN (0, 1)),
    label_timestamp TIMESTAMPTZ NOT NULL,
    label_source TEXT NOT NULL,
    label_confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE fraud_labels
    ADD COLUMN IF NOT EXISTS annotator_id TEXT;

ALTER TABLE fraud_labels
    ADD COLUMN IF NOT EXISTS reason_code TEXT;

ALTER TABLE fraud_labels
    ADD COLUMN IF NOT EXISTS updated_timestamp TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_fraud_labels_label_timestamp
    ON fraud_labels (label_timestamp);

CREATE TABLE IF NOT EXISTS fraud_label_events (
    id BIGSERIAL PRIMARY KEY,
    transaction_id TEXT NOT NULL REFERENCES fraud_transactions(transaction_id),
    is_fraud INTEGER NOT NULL CHECK (is_fraud IN (0, 1)),
    label_timestamp TIMESTAMPTZ NOT NULL,
    label_source TEXT NOT NULL,
    label_confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    annotator_id TEXT,
    reason_code TEXT,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fraud_label_events_transaction_id
    ON fraud_label_events (transaction_id);

CREATE INDEX IF NOT EXISTS idx_fraud_label_events_label_timestamp
    ON fraud_label_events (label_timestamp);

CREATE OR REPLACE VIEW fraud_training_examples AS
SELECT
    t.transaction_id,
    t.customer_id,
    t.merchant_id,
    t.event_timestamp,
    t.amount AS transaction_amount,
    t.country AS transaction_country,
    t.merchant_category,
    l.is_fraud,
    l.label_timestamp,
    l.label_source,
    l.label_confidence,
    l.annotator_id,
    l.reason_code
FROM fraud_transactions t
JOIN fraud_labels l ON l.transaction_id = t.transaction_id
WHERE l.label_timestamp >= t.event_timestamp;
