package com.example.fraud.adapter.in.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class UpstreamInferenceExceptionMapper implements ExceptionMapper<IllegalStateException> {
    @Override
    public Response toResponse(IllegalStateException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(new ErrorResponse("upstream_inference_error", exception.getMessage()))
                .build();
    }
}
