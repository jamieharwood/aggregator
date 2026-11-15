package com.aggregator.workflow;

import org.springframework.http.MediaType;

/** The raw response of a downstream endpoint, whatever its status. */
public record DownstreamResponse(int status, MediaType contentType, byte[] body) {
}
