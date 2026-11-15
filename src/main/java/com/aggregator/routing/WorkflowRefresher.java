package com.aggregator.routing;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.aggregator.config.ProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Reloads the workflows at a fixed interval so that changes in the store take effect. */
@Component
public class WorkflowRefresher implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRefresher.class);

    private final WorkflowRegistry registry;

    private final Duration interval;

    private ScheduledExecutorService scheduler;

    public WorkflowRefresher(WorkflowRegistry registry, ProxyProperties properties) {
        this.registry = registry;
        this.interval = properties.workflowStore().refreshInterval();
    }

    @Override
    public void afterPropertiesSet() {
        if (interval.isZero() || interval.isNegative()) {
            log.info("Workflow reloading is disabled; workflows are read once at startup");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("workflow-refresh").factory());
        scheduler.scheduleWithFixedDelay(registry::refresh, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Workflows are reloaded every {} ms", interval.toMillis());
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
