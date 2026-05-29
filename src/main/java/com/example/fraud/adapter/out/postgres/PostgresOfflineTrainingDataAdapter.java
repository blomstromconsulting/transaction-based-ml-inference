package com.example.fraud.adapter.out.postgres;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.MerchantFeatureRow;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.out.OfflineTrainingDataPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class PostgresOfflineTrainingDataAdapter implements OfflineTrainingDataPort {
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean schemaInitEnabled;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public PostgresOfflineTrainingDataAdapter(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "fraud.offline-store.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "fraud.offline-store.schema-init", defaultValue = "true") boolean schemaInitEnabled,
            @ConfigProperty(name = "fraud.offline-store.jdbc-url") String jdbcUrl,
            @ConfigProperty(name = "fraud.offline-store.user") String user,
            @ConfigProperty(name = "fraud.offline-store.password") String password) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.schemaInitEnabled = schemaInitEnabled;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    @PostConstruct
    void initializeSchema() {
        if (!enabled || !schemaInitEnabled) {
            return;
        }
        execute("""
                CREATE TABLE IF NOT EXISTS fraud_transactions (
                    transaction_id TEXT PRIMARY KEY,
                    customer_id TEXT NOT NULL,
                    card_id TEXT NOT NULL,
                    merchant_id TEXT NOT NULL,
                    merchant_category TEXT NOT NULL,
                    amount DOUBLE PRECISION NOT NULL,
                    currency TEXT NOT NULL,
                    country TEXT NOT NULL,
                    event_timestamp TIMESTAMPTZ NOT NULL,
                    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS fraud_labels (
                    transaction_id TEXT PRIMARY KEY REFERENCES fraud_transactions(transaction_id),
                    is_fraud INTEGER NOT NULL CHECK (is_fraud IN (0, 1)),
                    label_timestamp TIMESTAMPTZ NOT NULL,
                    label_source TEXT NOT NULL,
                    label_confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS customer_transaction_stats (
                    customer_id TEXT NOT NULL,
                    event_timestamp TIMESTAMPTZ NOT NULL,
                    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                    customer_transaction_count_1h BIGINT NOT NULL,
                    customer_transaction_count_24h BIGINT NOT NULL,
                    customer_total_amount_24h DOUBLE PRECISION NOT NULL,
                    customer_avg_amount_7d DOUBLE PRECISION NOT NULL,
                    customer_max_amount_7d DOUBLE PRECISION NOT NULL,
                    customer_distinct_merchants_24h BIGINT NOT NULL,
                    customer_cross_border_count_7d BIGINT NOT NULL,
                    PRIMARY KEY (customer_id, event_timestamp)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS merchant_risk_features (
                    merchant_id TEXT NOT NULL,
                    event_timestamp TIMESTAMPTZ NOT NULL,
                    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                    merchant_risk_score REAL NOT NULL,
                    merchant_category TEXT NOT NULL,
                    PRIMARY KEY (merchant_id, event_timestamp)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS fraud_prediction_logs (
                    transaction_id TEXT NOT NULL,
                    model TEXT NOT NULL,
                    model_version TEXT NOT NULL,
                    fraud_score DOUBLE PRECISION NOT NULL,
                    decision TEXT NOT NULL,
                    features_used JSONB NOT NULL,
                    inference_timestamp TIMESTAMPTZ NOT NULL,
                    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (transaction_id, model, model_version)
                )
                """);
        execute("""
                CREATE OR REPLACE VIEW fraud_training_examples AS
                SELECT
                    t.transaction_id,
                    t.customer_id,
                    t.merchant_id,
                    t.event_timestamp,
                    t.amount AS transaction_amount,
                    t.country AS transaction_country,
                    t.merchant_category,
                    l.is_fraud
                FROM fraud_transactions t
                JOIN fraud_labels l ON l.transaction_id = t.transaction_id
                """);
    }

    @Override
    public void recordTransaction(TransactionEvent event) {
        if (!enabled) {
            return;
        }
        execute("""
                INSERT INTO fraud_transactions (
                    transaction_id, customer_id, card_id, merchant_id, merchant_category,
                    amount, currency, country, event_timestamp
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO UPDATE
                SET customer_id = EXCLUDED.customer_id,
                    card_id = EXCLUDED.card_id,
                    merchant_id = EXCLUDED.merchant_id,
                    merchant_category = EXCLUDED.merchant_category,
                    amount = EXCLUDED.amount,
                    currency = EXCLUDED.currency,
                    country = EXCLUDED.country,
                    event_timestamp = EXCLUDED.event_timestamp
                """, statement -> {
            statement.setString(1, event.transactionId());
            statement.setString(2, event.customerId());
            statement.setString(3, event.cardId());
            statement.setString(4, event.merchantId());
            statement.setString(5, event.merchantCategory());
            statement.setBigDecimal(6, event.amount());
            statement.setString(7, event.currency());
            statement.setString(8, event.country());
            statement.setTimestamp(9, timestamp(event.timestamp()));
        });
    }

    @Override
    public void recordCustomerFeatures(CustomerFeatureRow row) {
        if (!enabled) {
            return;
        }
        execute("""
                INSERT INTO customer_transaction_stats (
                    customer_id, event_timestamp, customer_transaction_count_1h,
                    customer_transaction_count_24h, customer_total_amount_24h,
                    customer_avg_amount_7d, customer_max_amount_7d,
                    customer_distinct_merchants_24h, customer_cross_border_count_7d
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (customer_id, event_timestamp) DO UPDATE
                SET customer_transaction_count_1h = EXCLUDED.customer_transaction_count_1h,
                    customer_transaction_count_24h = EXCLUDED.customer_transaction_count_24h,
                    customer_total_amount_24h = EXCLUDED.customer_total_amount_24h,
                    customer_avg_amount_7d = EXCLUDED.customer_avg_amount_7d,
                    customer_max_amount_7d = EXCLUDED.customer_max_amount_7d,
                    customer_distinct_merchants_24h = EXCLUDED.customer_distinct_merchants_24h,
                    customer_cross_border_count_7d = EXCLUDED.customer_cross_border_count_7d
                """, statement -> {
            statement.setString(1, row.customerId());
            statement.setTimestamp(2, timestamp(row.eventTimestamp()));
            statement.setLong(3, row.customerTransactionCount1h());
            statement.setLong(4, row.customerTransactionCount24h());
            statement.setBigDecimal(5, row.customerTotalAmount24h());
            statement.setBigDecimal(6, row.customerAvgAmount7d());
            statement.setBigDecimal(7, row.customerMaxAmount7d());
            statement.setLong(8, row.customerDistinctMerchants24h());
            statement.setLong(9, row.customerCrossBorderCount7d());
        });
    }

    @Override
    public void recordMerchantFeatures(MerchantFeatureRow row) {
        if (!enabled) {
            return;
        }
        execute("""
                INSERT INTO merchant_risk_features (
                    merchant_id, event_timestamp, merchant_risk_score, merchant_category
                )
                VALUES (?, ?, ?, ?)
                ON CONFLICT (merchant_id, event_timestamp) DO UPDATE
                SET merchant_risk_score = EXCLUDED.merchant_risk_score,
                    merchant_category = EXCLUDED.merchant_category
                """, statement -> {
            statement.setString(1, row.merchantId());
            statement.setTimestamp(2, timestamp(row.eventTimestamp()));
            statement.setBigDecimal(3, row.merchantRiskScore());
            statement.setString(4, row.merchantCategory());
        });
    }

    @Override
    public void recordPrediction(FraudDecision decision) {
        if (!enabled) {
            return;
        }
        execute("""
                INSERT INTO fraud_prediction_logs (
                    transaction_id, model, model_version, fraud_score, decision,
                    features_used, inference_timestamp
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (transaction_id, model, model_version) DO UPDATE
                SET fraud_score = EXCLUDED.fraud_score,
                    decision = EXCLUDED.decision,
                    features_used = EXCLUDED.features_used,
                    inference_timestamp = EXCLUDED.inference_timestamp
                """, statement -> {
            statement.setString(1, decision.transactionId());
            statement.setString(2, decision.model().name());
            statement.setString(3, "demo");
            statement.setBigDecimal(4, decision.inferenceResult().fraudScore());
            statement.setString(5, decision.decision().name());
            statement.setString(6, json(decision.inferenceResult().featuresUsed()));
            statement.setTimestamp(7, timestamp(decision.decidedAt()));
        });
    }

    private void execute(String sql) {
        execute(sql, statement -> {
        });
    }

    private void execute(String sql, SqlBinder binder) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            Log.warnf(e, "Failed to write fraud offline training data");
            throw new IllegalStateException("Failed to write fraud offline training data", e);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize feature list", e);
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
