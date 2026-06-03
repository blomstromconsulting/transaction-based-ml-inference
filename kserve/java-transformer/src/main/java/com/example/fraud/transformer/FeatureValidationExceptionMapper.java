package com.example.fraud.transformer;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class FeatureValidationExceptionMapper implements ExceptionMapper<FeatureValidationException> {
    @Override
    public Response toResponse(FeatureValidationException exception) {
        return Response.status(422)
                .entity(Map.of(
                        "error", "FEATURE_VALIDATION_FAILED",
                        "message", exception.getMessage()))
                .build();
    }
}
