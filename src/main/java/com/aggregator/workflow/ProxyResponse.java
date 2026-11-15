package com.aggregator.workflow;

import org.springframework.http.MediaType;

/** The response returned to the caller of the proxy. */
public record ProxyResponse(int status, MediaType contentType, byte[] body) {
}
