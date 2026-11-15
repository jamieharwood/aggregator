package com.aggregator.workflow;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** A single HTTP call to a downstream endpoint. {@code body} is {@code null} when none is sent. */
public record DownstreamRequest(HttpMethod method, URI uri, HttpHeaders headers, byte[] body) {
}
