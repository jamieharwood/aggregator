package com.aggregator.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings. Workflows themselves are not configured here: they are stored in MongoDB.
 *
 * @param connectTimeout TCP connect timeout for all downstream calls
 */
@ConfigurationProperties("proxy")
public record ProxyProperties(Duration connectTimeout, WorkflowStore workflowStore) {

    public ProxyProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        workflowStore = workflowStore == null ? new WorkflowStore(null, null) : workflowStore;
    }

    /**
     * @param collection the MongoDB collection holding one document per workflow
     * @param refreshInterval how often workflows are reloaded; zero disables reloading
     */
    public record WorkflowStore(String collection, Duration refreshInterval) {

        public WorkflowStore {
            collection = collection == null || collection.isBlank() ? "workflows" : collection;
            refreshInterval = refreshInterval == null ? Duration.ofSeconds(30) : refreshInterval;
        }
    }
}
