package com.example.fraud.adapter.in.rest;

import com.example.fraud.domain.model.TransactionNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class TransactionNotFoundExceptionMapper implements ExceptionMapper<TransactionNotFoundException> {
    @Override
    public Response toResponse(TransactionNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("transaction_not_found", exception.getMessage()))
                .build();
    }
}
