package com.aggregator.workflow;

import com.aggregator.routing.Endpoint;

import org.springframework.http.MediaType;

/**
 * The outcome of calling one endpoint.
 *
 * @param status the downstream HTTP status, or {@code null} if no response was received
 * @param error a description of the failure, or {@code null} on success
 */
public record EndpointResult(
        Endpoint endpoint,
        Outcome outcome,
        Integer status,
        MediaType contentType,
        byte[] body,
        String error) {

    public enum Outcome {
        SUCCESS, FAILURE, TIMEOUT;

        public String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCESS;
    }
}
