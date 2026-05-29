package com.example.fraud.adapter.out.redis;

import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.out.CustomerTransactionStatsPort;
import io.quarkus.logging.Log;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class RedisCustomerTransactionStatsAdapter implements CustomerTransactionStatsPort {
    private static final Duration STATS_TTL = Duration.ofDays(8);
    private static final Duration WINDOW_1H = Duration.ofHours(1);
    private static final Duration WINDOW_24H = Duration.ofHours(24);
    private static final Duration WINDOW_7D = Duration.ofDays(7);

    private final Redis redis;

    public RedisCustomerTransactionStatsAdapter(Redis redis) {
        this.redis = redis;
    }

    @Override
    public CustomerTransactionStats updateStats(TransactionEvent event) {
        String statsKey = "fraud:customer:%s:stats".formatted(event.customerId());
        String transactionKey = "fraud:customer:%s:tx".formatted(event.customerId());
        String eventKey = "fraud:customer:%s:tx-events".formatted(event.customerId());

        BigDecimal amount = event.amount();
        long amountCents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long eventTime = event.timestamp().toEpochMilli();
        String member = "%s\t%d\t%s\t%s".formatted(
                event.transactionId(),
                amountCents,
                event.merchantId(),
                event.country());

        send(Request.cmd(Command.ZADD).arg(eventKey).arg(eventTime).arg(member));
        long oldestRetained = event.timestamp().minus(WINDOW_7D).toEpochMilli();
        send(Request.cmd(Command.ZREMRANGEBYSCORE).arg(eventKey).arg(0).arg(oldestRetained - 1));

        send(Request.cmd(Command.HSET)
                .arg(transactionKey)
                .arg(event.transactionId())
                .arg(event.timestamp().toString()));
        expire(transactionKey, STATS_TTL);
        expire(eventKey, STATS_TTL);

        CustomerTransactionStats stats = computeStats(eventKey, event.timestamp());
        writeStats(statsKey, stats);
        expire(statsKey, STATS_TTL);

        return stats;
    }

    private CustomerTransactionStats computeStats(String eventKey, Instant timestamp) {
        long count1h = zcount(eventKey, timestamp.minus(WINDOW_1H), timestamp);
        WindowStats stats24h = readWindowStats(eventKey, timestamp.minus(WINDOW_24H), timestamp);
        WindowStats stats7d = readWindowStats(eventKey, timestamp.minus(WINDOW_7D), timestamp);

        long count24h = stats24h.count();
        long count7d = Math.max(1L, stats7d.count());
        BigDecimal total24h = cents(stats24h.totalAmountCents());
        BigDecimal avg7d = cents(stats7d.totalAmountCents())
                .divide(BigDecimal.valueOf(count7d), 2, RoundingMode.HALF_UP);
        BigDecimal max7d = cents(stats7d.maxAmountCents());
        long merchants24h = stats24h.distinctMerchants();
        long crossBorder7d = stats7d.crossBorderCount();
        return new CustomerTransactionStats(count1h, count24h, total24h, avg7d, max7d, merchants24h, crossBorder7d);
    }

    private void writeStats(String statsKey, CustomerTransactionStats stats) {
        send(Request.cmd(Command.HSET)
                .arg(statsKey)
                .arg("customer_transaction_count_1h")
                .arg(stats.transactionCount1h())
                .arg("customer_transaction_count_24h")
                .arg(stats.transactionCount24h())
                .arg("customer_total_amount_24h_cents")
                .arg(toCents(stats.totalAmount24h()))
                .arg("customer_avg_amount_7d_cents")
                .arg(toCents(stats.averageAmount7d()))
                .arg("customer_max_amount_7d_cents")
                .arg(toCents(stats.maxAmount7d()))
                .arg("customer_distinct_merchants_24h")
                .arg(stats.distinctMerchants24h())
                .arg("customer_cross_border_count_7d")
                .arg(stats.crossBorderCount7d()));
    }

    private long zcount(String key, Instant fromInclusive, Instant toInclusive) {
        Response response = send(Request.cmd(Command.ZCOUNT)
                .arg(key)
                .arg(fromInclusive.toEpochMilli())
                .arg(toInclusive.toEpochMilli()));
        return response == null ? 0L : response.toLong();
    }

    private WindowStats readWindowStats(String key, Instant fromInclusive, Instant toInclusive) {
        Response response = send(Request.cmd(Command.ZRANGEBYSCORE)
                .arg(key)
                .arg(fromInclusive.toEpochMilli())
                .arg(toInclusive.toEpochMilli()));
        if (response == null) {
            return new WindowStats(0, 0, 0, 0, 0);
        }

        long count = 0;
        long totalAmountCents = 0;
        long maxAmountCents = 0;
        long crossBorderCount = 0;
        Set<String> merchants = new HashSet<>();

        for (Response item : response) {
            String[] parts = item.toString().split("\t", -1);
            if (parts.length < 4) {
                continue;
            }
            count++;
            long amountCents = Long.parseLong(parts[1]);
            totalAmountCents += amountCents;
            maxAmountCents = Math.max(maxAmountCents, amountCents);
            merchants.add(parts[2]);
            if (!"SE".equalsIgnoreCase(parts[3])) {
                crossBorderCount++;
            }
        }

        return new WindowStats(count, totalAmountCents, maxAmountCents, merchants.size(), crossBorderCount);
    }

    private void expire(String key, Duration ttl) {
        send(Request.cmd(Command.EXPIRE).arg(key).arg(ttl.toSeconds()));
    }

    private Response send(Request request) {
        try {
            return redis.send(request).await().indefinitely();
        } catch (RuntimeException e) {
            Log.warnf(e, "Redis command failed for demo aggregation");
            throw e;
        }
    }

    private BigDecimal cents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private long toCents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private record WindowStats(
            long count,
            long totalAmountCents,
            long maxAmountCents,
            long distinctMerchants,
            long crossBorderCount) {
    }
}
