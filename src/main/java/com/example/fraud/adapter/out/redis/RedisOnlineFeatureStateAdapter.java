package com.example.fraud.adapter.out.redis;

import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.CustomerFeatureRow;
import com.example.fraud.domain.model.MerchantFeatureRow;
import com.example.fraud.domain.model.MerchantVisitFeatures;
import com.example.fraud.domain.model.OnlineFeatureSnapshot;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.out.OnlineFeatureStatePort;
import io.quarkus.logging.Log;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class RedisOnlineFeatureStateAdapter implements OnlineFeatureStatePort {
    private static final Duration STATS_TTL = Duration.ofDays(8);
    private static final Duration WINDOW_1H = Duration.ofHours(1);
    private static final Duration WINDOW_24H = Duration.ofHours(24);
    private static final Duration WINDOW_7D = Duration.ofDays(7);
    private static final int MERCHANT_VISIT_GRACE_DAYS = 2;
    private static final String ROLLING_TRANSACTION_UPDATE_SCRIPT = """
            if redis.call('SETNX', KEYS[3], ARGV[1]) == 0 then
              return 0
            end
            redis.call('EXPIRE', KEYS[3], ARGV[5])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
            redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            return 1
            """;
    private static final String MERCHANT_BUCKET_UPDATE_SCRIPT = """
            if redis.call('SETNX', KEYS[4], ARGV[2]) == 0 then
              return 0
            end
            redis.call('EXPIRE', KEYS[4], ARGV[3])
            redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
            local first = redis.call('HGET', KEYS[2], ARGV[1])
            if (not first) or tonumber(first) > tonumber(ARGV[2]) then
              redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
            end
            local last = redis.call('HGET', KEYS[3], ARGV[1])
            if (not last) or tonumber(last) < tonumber(ARGV[2]) then
              redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
            end
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[3], ARGV[3])
            return 1
            """;

    private final Redis redis;
    private final String homeCountry;
    private final int merchantVisitWindowDays;

    public RedisOnlineFeatureStateAdapter(
            Redis redis,
            @ConfigProperty(name = "fraud.features.home-country", defaultValue = "SE") String homeCountry,
            @ConfigProperty(name = "fraud.features.merchant-visit-window-days", defaultValue = "30")
            int merchantVisitWindowDays) {
        this.redis = redis;
        this.homeCountry = homeCountry.toUpperCase();
        if (merchantVisitWindowDays < 1) {
            throw new IllegalArgumentException("fraud.features.merchant-visit-window-days must be at least 1");
        }
        this.merchantVisitWindowDays = merchantVisitWindowDays;
    }

    @Override
    public OnlineFeatureSnapshot updateAndSnapshot(TransactionEvent event) {
        MerchantVisitFeatures merchantVisitFeatures = snapshotMerchantVisits(event);
        CustomerTransactionStats stats = updateRollingStats(event);
        CustomerFeatureRow customerFeatureRow = CustomerFeatureRow.from(event, stats, merchantVisitFeatures);
        MerchantFeatureRow merchantFeatureRow = MerchantFeatureRow.from(event);
        updateMerchantVisitBucket(event);
        return new OnlineFeatureSnapshot(customerFeatureRow, merchantFeatureRow);
    }

    private CustomerTransactionStats updateRollingStats(TransactionEvent event) {
        String eventKey = rollingEventKey(event.customerId());
        String detailKey = rollingDetailKey(event.customerId());

        BigDecimal amount = event.amount();
        long amountCents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long eventTime = event.timestamp().toEpochMilli();
        String detail = "%d\t%s\t%s".formatted(amountCents, event.merchantId(), event.country());
        long oldestRetainedExclusive = event.timestamp().minus(WINDOW_7D).toEpochMilli() - 1;
        int ttlSeconds = Math.toIntExact(STATS_TTL.toSeconds());

        send(Request.cmd(Command.EVAL)
                .arg(ROLLING_TRANSACTION_UPDATE_SCRIPT)
                .arg(3)
                .arg(eventKey)
                .arg(detailKey)
                .arg(rollingTransactionMarkerKey(event.transactionId()))
                .arg(event.transactionId())
                .arg(eventTime)
                .arg(oldestRetainedExclusive)
                .arg(detail)
                .arg(ttlSeconds));

        return computeStats(eventKey, detailKey, event.timestamp());
    }

    private CustomerTransactionStats computeStats(String eventKey, String detailKey, Instant timestamp) {
        long count1h = zcount(eventKey, timestamp.minus(WINDOW_1H), timestamp);
        WindowStats stats24h = readWindowStats(eventKey, detailKey, timestamp.minus(WINDOW_24H), timestamp);
        WindowStats stats7d = readWindowStats(eventKey, detailKey, timestamp.minus(WINDOW_7D), timestamp);

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

    private long zcount(String key, Instant fromInclusive, Instant toInclusive) {
        Response response = send(Request.cmd(Command.ZCOUNT)
                .arg(key)
                .arg(fromInclusive.toEpochMilli())
                .arg(toInclusive.toEpochMilli()));
        return response == null ? 0L : response.toLong();
    }

    private WindowStats readWindowStats(String eventKey, String detailKey, Instant fromInclusive, Instant toInclusive) {
        Response response = send(Request.cmd(Command.ZRANGEBYSCORE)
                .arg(eventKey)
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
            Response detailResponse = send(Request.cmd(Command.HGET)
                    .arg(detailKey)
                    .arg(item.toString()));
            if (detailResponse == null) {
                continue;
            }
            String[] parts = detailResponse.toString().split("\t", -1);
            if (parts.length < 3) {
                continue;
            }
            count++;
            long amountCents = Long.parseLong(parts[0]);
            totalAmountCents += amountCents;
            maxAmountCents = Math.max(maxAmountCents, amountCents);
            merchants.add(parts[1]);
            if (!homeCountry.equalsIgnoreCase(parts[2])) {
                crossBorderCount++;
            }
        }

        return new WindowStats(count, totalAmountCents, maxAmountCents, merchants.size(), crossBorderCount);
    }

    private MerchantVisitFeatures snapshotMerchantVisits(TransactionEvent event) {
        Map<String, MerchantVisitAggregate> aggregates = readMerchantVisitWindow(event);
        long totalVisits = aggregates.values().stream()
                .mapToLong(MerchantVisitAggregate::count)
                .sum();
        long distinctMerchants = aggregates.values().stream()
                .filter(aggregate -> aggregate.count() > 0)
                .count();

        MerchantVisitAggregate currentMerchant = aggregates.get(event.merchantId());
        long currentCount = currentMerchant == null ? 0 : currentMerchant.count();
        BigDecimal currentShare = totalVisits == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(currentCount)
                .divide(BigDecimal.valueOf(totalVisits), 6, RoundingMode.HALF_UP);

        RankedMerchant topMerchant = topMerchant(aggregates);
        long currentRank = rank(event.merchantId(), aggregates);
        boolean currentIsTop = currentRank == 1 && currentCount > 0;
        BigDecimal daysSinceFirstSeen = currentMerchant == null
                ? BigDecimal.valueOf(-1)
                : daysBetween(currentMerchant.firstSeenEpochMs(), event.timestamp());
        BigDecimal daysSinceLastSeen = currentMerchant == null
                ? BigDecimal.valueOf(-1)
                : daysBetween(currentMerchant.lastSeenEpochMs(), event.timestamp());

        return new MerchantVisitFeatures(
                currentCount,
                currentShare,
                currentRank,
                currentIsTop ? 1 : 0,
                daysSinceFirstSeen,
                daysSinceLastSeen,
                distinctMerchants,
                currentCount == 0 ? 1 : 0,
                topMerchant == null ? "" : topMerchant.merchantId());
    }

    private Map<String, MerchantVisitAggregate> readMerchantVisitWindow(TransactionEvent event) {
        Map<String, MerchantVisitAggregate> aggregates = new HashMap<>();
        LocalDate eventDate = LocalDate.ofInstant(event.timestamp(), ZoneOffset.UTC);
        LocalDate startDate = eventDate.minusDays((long) merchantVisitWindowDays - 1);
        for (LocalDate date = startDate; !date.isAfter(eventDate); date = date.plusDays(1)) {
            String suffix = merchantVisitBucketSuffix(date);
            mergeCounts(aggregates, readHash(merchantCountsKey(event.customerId(), suffix)));
            mergeFirstSeen(aggregates, readHash(merchantFirstSeenKey(event.customerId(), suffix)));
            mergeLastSeen(aggregates, readHash(merchantLastSeenKey(event.customerId(), suffix)));
        }
        return aggregates;
    }

    private void updateMerchantVisitBucket(TransactionEvent event) {
        String suffix = merchantVisitBucketSuffix(LocalDate.ofInstant(event.timestamp(), ZoneOffset.UTC));
        int ttlSeconds = Math.toIntExact(Duration.ofDays((long) merchantVisitWindowDays + MERCHANT_VISIT_GRACE_DAYS).toSeconds());
        send(Request.cmd(Command.EVAL)
                .arg(MERCHANT_BUCKET_UPDATE_SCRIPT)
                .arg(4)
                .arg(merchantCountsKey(event.customerId(), suffix))
                .arg(merchantFirstSeenKey(event.customerId(), suffix))
                .arg(merchantLastSeenKey(event.customerId(), suffix))
                .arg(merchantVisitTransactionMarkerKey(event.transactionId()))
                .arg(event.merchantId())
                .arg(event.timestamp().toEpochMilli())
                .arg(ttlSeconds));
    }

    private String merchantVisitBucketSuffix(LocalDate date) {
        return date.toString();
    }

    private String rollingEventKey(String customerId) {
        return "fraud:feature-state:customer:%s:tx-events".formatted(customerId);
    }

    private String rollingDetailKey(String customerId) {
        return "fraud:feature-state:customer:%s:tx-details".formatted(customerId);
    }

    private String rollingTransactionMarkerKey(String transactionId) {
        return "fraud:feature-state:transaction:%s:rolling".formatted(transactionId);
    }

    private String merchantCountsKey(String customerId, String suffix) {
        return "fraud:feature-state:customer:%s:merchant-counts:%s".formatted(customerId, suffix);
    }

    private String merchantFirstSeenKey(String customerId, String suffix) {
        return "fraud:feature-state:customer:%s:merchant-first-seen:%s".formatted(customerId, suffix);
    }

    private String merchantLastSeenKey(String customerId, String suffix) {
        return "fraud:feature-state:customer:%s:merchant-last-seen:%s".formatted(customerId, suffix);
    }

    private String merchantVisitTransactionMarkerKey(String transactionId) {
        return "fraud:feature-state:transaction:%s:merchant-visits".formatted(transactionId);
    }

    private Map<String, String> readHash(String key) {
        Response response = send(Request.cmd(Command.HGETALL).arg(key));
        Map<String, String> values = new HashMap<>();
        if (response == null) {
            return values;
        }
        if (response.isMap()) {
            for (String fieldName : response.getKeys()) {
                Response value = response.get(fieldName);
                if (value != null) {
                    values.put(fieldName, value.toString());
                }
            }
            return values;
        }
        String name = null;
        for (Response item : response) {
            if (name == null) {
                name = item.toString();
            } else {
                values.put(name, item.toString());
                name = null;
            }
        }
        return values;
    }

    private void mergeCounts(Map<String, MerchantVisitAggregate> aggregates, Map<String, String> counts) {
        counts.forEach((merchantId, rawCount) -> aggregate(aggregates, merchantId)
                .addCount(Long.parseLong(rawCount)));
    }

    private void mergeFirstSeen(Map<String, MerchantVisitAggregate> aggregates, Map<String, String> firstSeen) {
        firstSeen.forEach((merchantId, rawTimestamp) -> aggregate(aggregates, merchantId)
                .recordFirstSeen(Long.parseLong(rawTimestamp)));
    }

    private void mergeLastSeen(Map<String, MerchantVisitAggregate> aggregates, Map<String, String> lastSeen) {
        lastSeen.forEach((merchantId, rawTimestamp) -> aggregate(aggregates, merchantId)
                .recordLastSeen(Long.parseLong(rawTimestamp)));
    }

    private MerchantVisitAggregate aggregate(Map<String, MerchantVisitAggregate> aggregates, String merchantId) {
        return aggregates.computeIfAbsent(merchantId, ignored -> new MerchantVisitAggregate());
    }

    private RankedMerchant topMerchant(Map<String, MerchantVisitAggregate> aggregates) {
        return aggregates.entrySet().stream()
                .filter(entry -> entry.getValue().count() > 0)
                .map(entry -> new RankedMerchant(entry.getKey(), entry.getValue().count()))
                .min(Comparator.comparingLong(RankedMerchant::count).reversed()
                        .thenComparing(RankedMerchant::merchantId))
                .orElse(null);
    }

    private long rank(String merchantId, Map<String, MerchantVisitAggregate> aggregates) {
        MerchantVisitAggregate current = aggregates.get(merchantId);
        if (current == null || current.count() == 0) {
            return 0;
        }
        return aggregates.entrySet().stream()
                .filter(entry -> entry.getValue().count() > 0)
                .map(entry -> new RankedMerchant(entry.getKey(), entry.getValue().count()))
                .sorted(Comparator.comparingLong(RankedMerchant::count).reversed()
                        .thenComparing(RankedMerchant::merchantId))
                .map(RankedMerchant::merchantId)
                .toList()
                .indexOf(merchantId) + 1L;
    }

    private BigDecimal daysBetween(long priorEpochMs, Instant currentTimestamp) {
        BigDecimal milliseconds = BigDecimal.valueOf(
                Math.max(0L, currentTimestamp.toEpochMilli() - priorEpochMs));
        return milliseconds.divide(BigDecimal.valueOf(86_400_000L), 6, RoundingMode.HALF_UP);
    }

    private Response send(Request request) {
        try {
            return redis.send(request).await().indefinitely();
        } catch (RuntimeException e) {
            Log.warnf(e, "Redis command failed for online feature state");
            throw e;
        }
    }

    private BigDecimal cents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private record WindowStats(
            long count,
            long totalAmountCents,
            long maxAmountCents,
            long distinctMerchants,
            long crossBorderCount) {
    }

    private static final class MerchantVisitAggregate {
        private long count;
        private long firstSeenEpochMs = Long.MAX_VALUE;
        private long lastSeenEpochMs = Long.MIN_VALUE;

        void addCount(long count) {
            this.count += count;
        }

        void recordFirstSeen(long epochMs) {
            firstSeenEpochMs = Math.min(firstSeenEpochMs, epochMs);
        }

        void recordLastSeen(long epochMs) {
            lastSeenEpochMs = Math.max(lastSeenEpochMs, epochMs);
        }

        long count() {
            return count;
        }

        long firstSeenEpochMs() {
            return firstSeenEpochMs;
        }

        long lastSeenEpochMs() {
            return lastSeenEpochMs;
        }
    }

    private record RankedMerchant(String merchantId, long count) {
    }
}
