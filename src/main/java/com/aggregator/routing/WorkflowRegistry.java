package com.aggregator.routing;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Holds the workflows currently in force. They are loaded from the repository at startup, where
 * an invalid set stops the application from starting, and again on every {@link #refresh()},
 * where an invalid set is rejected and the previous workflows stay in force.
 */
@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final WorkflowDefinitionRepository repository;

    private volatile Loaded loaded;

    public WorkflowRegistry(WorkflowDefinitionRepository repository) {
        this.repository = repository;
        Map<String, WorkflowDefinition> definitions = repository.findAll();
        this.loaded = new Loaded(definitions, new WorkflowTable(definitions));
        log.info("Loaded {} workflows: {}", definitions.size(), definitions.keySet());
    }

    public Workflow resolve(String path, HttpMethod method) {
        return loaded.table().resolve(path, method);
    }

    public List<Workflow> workflows() {
        return loaded.table().workflows();
    }

    /**
     * Reloads the workflows. Requests already in progress finish on the workflow they started with.
     *
     * @return {@code true} if the workflows changed
     */
    public synchronized boolean refresh() {
        try {
            Map<String, WorkflowDefinition> definitions = repository.findAll();
            if (definitions.equals(loaded.definitions())) {
                return false;
            }
            loaded = new Loaded(definitions, new WorkflowTable(definitions));
            log.info("Reloaded {} workflows: {}", definitions.size(), definitions.keySet());
            return true;
        } catch (RuntimeException ex) {
            log.error("Workflow reload failed; keeping the {} workflows already loaded. Cause: {}",
                    loaded.definitions().size(), ex.toString());
            return false;
        }
    }

    private record Loaded(Map<String, WorkflowDefinition> definitions, WorkflowTable table) {
    }
}
