package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.CustomerTransactionStats;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.in.UpdateCustomerTransactionStatsUseCase;
import com.example.fraud.domain.port.out.CustomerTransactionStatsPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UpdateCustomerTransactionStatsService implements UpdateCustomerTransactionStatsUseCase {
    private final CustomerTransactionStatsPort statsPort;

    public UpdateCustomerTransactionStatsService(CustomerTransactionStatsPort statsPort) {
        this.statsPort = statsPort;
    }

    @Override
    public CustomerTransactionStats update(TransactionEvent event) {
        return statsPort.updateStats(event);
    }
}
