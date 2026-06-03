package com.example.fraud.adapter.out.postgres;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.ConfirmedFraudLabel;
import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.MerchantFeatureRow;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.out.FraudLabelRepositoryPort;
import com.example.fraud.domain.port.out.OfflineDataSinkPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class PostgresOfflineDataSinkAdapter implements OfflineDataSinkPort, FraudLabelRepositoryPort {
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final boolean enabled;

    public PostgresOfflineDataSinkAdapter(
            ObjectMapper objectMapper,
            DataSource dataSource,
            @ConfigProperty(name = "fraud.offline-store.enabled", defaultValue = "false") boolean enabled) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public boolean transactionExists(String transactionId) {
        if (!enabled) {
            throw new IllegalStateException("Offline store must be enabled to ingest fraud labels");
        }
        return executeExists("""
                SELECT 1
                FROM fraud_transactions
                WHERE transaction_id = ?
                """, statement -> statement.setString(1, transactionId));
    }

    @Override
    public void upsertLabel(ConfirmedFraudLabel label) {
        if (!enabled) {
            throw new IllegalStateException("Offline store must be enabled to ingest fraud labels");
        }
        execute("""
                INSERT INTO fraud_label_events (
                    transaction_id, is_fraud, label_timestamp, label_source,
                    label_confidence, annotator_id, reason_code
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, statement -> bindLabel(statement, label));
        execute("""
                INSERT INTO fraud_labels (
                    transaction_id, is_fraud, label_timestamp, label_source,
                    label_confidence, annotator_id, reason_code
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO UPDATE
                SET is_fraud = EXCLUDED.is_fraud,
                    label_timestamp = EXCLUDED.label_timestamp,
                    label_source = EXCLUDED.label_source,
                    label_confidence = EXCLUDED.label_confidence,
                    annotator_id = EXCLUDED.annotator_id,
                    reason_code = EXCLUDED.reason_code,
                    updated_timestamp = now()
                """, statement -> bindLabel(statement, label));
    }

    @Override
    public boolean recordProcessingStarted(TransactionEvent event) {
        if (!enabled) {
            return true;
        }
        int inserted = executeUpdate("""
                INSERT INTO fraud_transaction_processing (
                    transaction_id, model, status
                )
                VALUES (?, ?, 'STARTED')
                ON CONFLICT (transaction_id) DO NOTHING
                """, statement -> {
            statement.setString(1, event.transactionId());
            statement.setString(2, event.requestedModel());
        });
        if (inserted > 0) {
            return true;
        }

        int restarted = executeUpdate("""
                UPDATE fraud_transaction_processing
                SET model = ?,
                    status = 'STARTED',
                    error_message = NULL,
                    updated_timestamp = now()
                WHERE transaction_id = ?
                  AND status = 'FAILED'
                """, statement -> {
            statement.setString(1, event.requestedModel());
            statement.setString(2, event.transactionId());
        });
        return restarted > 0;
    }

    @Override
    public void recordProcessingFailure(TransactionEvent event, RuntimeException failure) {
        if (!enabled) {
            return;
        }
        execute("""
                UPDATE fraud_transaction_processing
                SET status = 'FAILED',
                    error_message = ?,
                    updated_timestamp = now()
                WHERE transaction_id = ?
                """, statement -> {
            statement.setString(1, truncate(failure.getMessage(), 1000));
            statement.setString(2, event.transactionId());
        });
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
                    customer_distinct_merchants_24h, customer_cross_border_count_7d,
                    current_merchant_visit_count_30d, current_merchant_visit_share_30d,
                    current_merchant_rank_30d, is_current_merchant_top_visited_30d,
                    days_since_first_seen_current_merchant, days_since_last_seen_current_merchant,
                    customer_distinct_merchants_30d, is_new_merchant_for_customer,
                    top_visited_merchant_id_30d
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (customer_id, event_timestamp) DO UPDATE
                SET customer_transaction_count_1h = EXCLUDED.customer_transaction_count_1h,
                    customer_transaction_count_24h = EXCLUDED.customer_transaction_count_24h,
                    customer_total_amount_24h = EXCLUDED.customer_total_amount_24h,
                    customer_avg_amount_7d = EXCLUDED.customer_avg_amount_7d,
                    customer_max_amount_7d = EXCLUDED.customer_max_amount_7d,
                    customer_distinct_merchants_24h = EXCLUDED.customer_distinct_merchants_24h,
                    customer_cross_border_count_7d = EXCLUDED.customer_cross_border_count_7d,
                    current_merchant_visit_count_30d = EXCLUDED.current_merchant_visit_count_30d,
                    current_merchant_visit_share_30d = EXCLUDED.current_merchant_visit_share_30d,
                    current_merchant_rank_30d = EXCLUDED.current_merchant_rank_30d,
                    is_current_merchant_top_visited_30d = EXCLUDED.is_current_merchant_top_visited_30d,
                    days_since_first_seen_current_merchant = EXCLUDED.days_since_first_seen_current_merchant,
                    days_since_last_seen_current_merchant = EXCLUDED.days_since_last_seen_current_merchant,
                    customer_distinct_merchants_30d = EXCLUDED.customer_distinct_merchants_30d,
                    is_new_merchant_for_customer = EXCLUDED.is_new_merchant_for_customer,
                    top_visited_merchant_id_30d = EXCLUDED.top_visited_merchant_id_30d
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
            statement.setLong(10, row.currentMerchantVisitCount30d());
            statement.setBigDecimal(11, row.currentMerchantVisitShare30d());
            statement.setLong(12, row.currentMerchantRank30d());
            statement.setLong(13, row.currentMerchantTopVisited30d());
            statement.setBigDecimal(14, row.daysSinceFirstSeenCurrentMerchant());
            statement.setBigDecimal(15, row.daysSinceLastSeenCurrentMerchant());
            statement.setLong(16, row.customerDistinctMerchants30d());
            statement.setLong(17, row.newMerchantForCustomer());
            statement.setString(18, row.topVisitedMerchantId30d());
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
            statement.setString(2, decision.model());
            statement.setString(3, decision.modelVersion());
            statement.setBigDecimal(4, decision.inferenceResult().fraudScore());
            statement.setString(5, decision.decision().name());
            statement.setString(6, json(decision.inferenceResult().featuresUsed()));
            statement.setTimestamp(7, timestamp(decision.decidedAt()));
        });
        execute("""
                UPDATE fraud_transaction_processing
                SET status = 'PREDICTION_RECORDED',
                    error_message = NULL,
                    updated_timestamp = now()
                WHERE transaction_id = ?
                """, statement -> statement.setString(1, decision.transactionId()));
    }

    @Override
    public void recordDecisionPublished(FraudDecision decision) {
        if (!enabled) {
            return;
        }
        execute("""
                UPDATE fraud_transaction_processing
                SET status = 'DECISION_PUBLISHED',
                    error_message = NULL,
                    updated_timestamp = now()
                WHERE transaction_id = ?
                """, statement -> statement.setString(1, decision.transactionId()));
    }

    private void execute(String sql) {
        execute(sql, statement -> {
        });
    }

    private void execute(String sql, SqlBinder binder) {
        executeUpdate(sql, binder);
    }

    private int executeUpdate(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            Log.warnf(e, "Failed to write fraud offline data sink");
            throw new IllegalStateException("Failed to write fraud offline data sink", e);
        }
    }

    private boolean executeExists(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            Log.warnf(e, "Failed to read fraud offline data sink");
            throw new IllegalStateException("Failed to read fraud offline data sink", e);
        }
    }

    private void bindLabel(PreparedStatement statement, ConfirmedFraudLabel label) throws SQLException {
        statement.setString(1, label.transactionId());
        statement.setInt(2, label.fraud() ? 1 : 0);
        statement.setTimestamp(3, timestamp(label.labelTimestamp()));
        statement.setString(4, label.labelSource());
        statement.setBigDecimal(5, label.labelConfidence());
        statement.setString(6, label.annotatorId());
        statement.setString(7, label.reasonCode());
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
