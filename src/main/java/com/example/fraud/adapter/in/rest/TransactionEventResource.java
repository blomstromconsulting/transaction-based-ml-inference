package com.example.fraud.adapter.in.rest;

import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.FraudModel;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.in.ReceiveTransactionEventUseCase;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/transactions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TransactionEventResource {
    private final ReceiveTransactionEventUseCase receiveTransactionEventUseCase;

    public TransactionEventResource(ReceiveTransactionEventUseCase receiveTransactionEventUseCase) {
        this.receiveTransactionEventUseCase = receiveTransactionEventUseCase;
    }

    @POST
    public Response submit(TransactionEventRequest request) {
        FraudModel model = request.requestedModel() == null
                ? FraudModel.MODEL_A
                : FraudModel.valueOf(request.requestedModel());
        TransactionEvent event = new TransactionEvent(
                request.transactionId(),
                request.customerId(),
                request.cardId(),
                request.merchantId(),
                request.merchantCategory(),
                request.amount(),
                request.currency(),
                request.country(),
                request.timestamp(),
                model);
        FraudDecision decision = receiveTransactionEventUseCase.receive(event);
        return Response.accepted(FraudDecisionResponse.from(decision)).build();
    }
}
