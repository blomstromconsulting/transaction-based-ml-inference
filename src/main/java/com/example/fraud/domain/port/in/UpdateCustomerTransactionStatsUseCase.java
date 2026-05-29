package com.example.fraud.domain.port.in;

import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.TransactionEvent;

public interface UpdateCustomerTransactionStatsUseCase {
    CustomerTransactionStats update(TransactionEvent event);
}
