package com.example.fraud.adapter.in.rest;

import com.example.fraud.domain.model.ConfirmedFraudLabel;
import com.example.fraud.domain.model.FraudDecision;
import com.example.fraud.domain.model.TransactionEvent;
import com.example.fraud.domain.port.in.IngestFraudLabelUseCase;
import com.example.fraud.domain.port.in.ReceiveTransactionEventUseCase;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/transactions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TransactionEventResource {
    private final ReceiveTransactionEventUseCase receiveTransactionEventUseCase;
    private final IngestFraudLabelUseCase ingestFraudLabelUseCase;

    public TransactionEventResource(
            ReceiveTransactionEventUseCase receiveTransactionEventUseCase,
            IngestFraudLabelUseCase ingestFraudLabelUseCase) {
        this.receiveTransactionEventUseCase = receiveTransactionEventUseCase;
        this.ingestFraudLabelUseCase = ingestFraudLabelUseCase;
    }

    @POST
    public Response submit(TransactionEventRequest request) {
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
                request.requestedModel());
        FraudDecision decision = receiveTransactionEventUseCase.receive(event);
        return Response.accepted(FraudDecisionResponse.from(decision)).build();
    }

    @PUT
    @Path("/{transactionId}/label")
    public Response label(@PathParam("transactionId") String transactionId, FraudLabelRequest request) {
        if (request.fraud() == null) {
            throw new IllegalArgumentException("is_fraud is required");
        }
        ConfirmedFraudLabel label = new ConfirmedFraudLabel(
                transactionId,
                request.fraud(),
                request.labelTimestamp(),
                request.labelSource(),
                request.labelConfidence(),
                request.annotatorId(),
                request.reasonCode());
        ConfirmedFraudLabel ingested = ingestFraudLabelUseCase.ingest(label);
        return Response.ok(FraudLabelResponse.from(ingested)).build();
    }
}
