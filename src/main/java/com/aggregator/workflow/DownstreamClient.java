package com.aggregator.workflow;

/** Performs the HTTP call to a downstream endpoint. Non-2xx statuses are returned, not thrown. */
public interface DownstreamClient {

    DownstreamResponse exchange(DownstreamRequest request) throws Exception;
}
