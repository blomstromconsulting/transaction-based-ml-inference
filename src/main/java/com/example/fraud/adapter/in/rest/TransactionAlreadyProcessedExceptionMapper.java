package com.example.fraud.adapter.in.rest;

import com.example.fraud.domain.model.TransactionAlreadyProcessedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class TransactionAlreadyProcessedExceptionMapper implements ExceptionMapper<TransactionAlreadyProcessedException> {
    @Override
    public Response toResponse(TransactionAlreadyProcessedException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse("already_processed", exception.getMessage()))
                .build();
    }
}
