package com.aggregator.routing;

import java.util.Map;

/** Loads the stored workflow definitions. */
public interface WorkflowDefinitionRepository {

    /**
     * @return all workflow definitions keyed by workflow name, in a stable order
     * @throws InvalidWorkflowDefinitionException if a stored definition cannot be read
     */
    Map<String, WorkflowDefinition> findAll();
}
