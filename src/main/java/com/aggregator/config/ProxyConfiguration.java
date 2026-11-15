package com.aggregator.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfiguration {

    /** One virtual thread per downstream call; used for the asynchronous stage and for timeouts. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService proxyExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("proxy-", 0).factory());
    }
}
