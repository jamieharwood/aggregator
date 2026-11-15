package com.aggregator.routing;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;

/**
 * A validated downstream endpoint.
 *
 * @param method the HTTP method to use, or {@code null} to inherit the inbound request's method
 */
public record Endpoint(
        String name,
        String url,
        HttpMethod method,
        Duration timeout,
        Map<String, String> headers,
        List<String> forwardHeaders,
        boolean appendInboundPath) {

    public boolean inheritsMethod() {
        return method == null;
    }
}
