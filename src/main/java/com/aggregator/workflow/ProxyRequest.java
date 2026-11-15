package com.aggregator.workflow;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * The inbound request, detached from the servlet API.
 *
 * @param path the raw (still encoded) request path
 * @param query the raw query string, or {@code null}
 */
public record ProxyRequest(
        HttpMethod method,
        String path,
        String query,
        HttpHeaders headers,
        MediaType contentType,
        byte[] body,
        String correlationId) {
}
