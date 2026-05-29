package com.example.fraud.adapter.out.feast;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "feast")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface FeastFeatureServerClient {
    @POST
    @Path("/get-online-features")
    FeastOnlineResponse getOnlineFeatures(FeastOnlineRequest request);
}
