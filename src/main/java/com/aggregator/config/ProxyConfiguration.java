package com.aggregator.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfiguration {

    private static final String DEFAULT_DATABASE = "aggregator";

    /** One virtual thread per downstream call; used for the asynchronous stage and for timeouts. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService proxyExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("proxy-", 0).factory());
    }

    /** The database named in the MongoDB connection string, holding the workflow collection. */
    @Bean
    MongoDatabase workflowDatabase(MongoClient client, MongoConnectionDetails connectionDetails) {
        String database = connectionDetails.getConnectionString().getDatabase();
        return client.getDatabase(database == null || database.isBlank() ? DEFAULT_DATABASE : database);
    }
}
