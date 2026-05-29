package com.example.fraud.adapter.in.messaging;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaTransactionEventConsumer {
    /*
     * Optional demo extension point.
     * Add quarkus-smallrye-reactive-messaging-kafka and consume transaction events here,
     * then delegate to ReceiveTransactionEventUseCase just like the REST adapter does.
     */
}
