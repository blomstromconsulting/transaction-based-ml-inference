package com.example.fraud.domain.port.out;

import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.TransactionEvent;

public interface CustomerTransactionStatsPort {
    CustomerTransactionStats updateStats(TransactionEvent event);
}
