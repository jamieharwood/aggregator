package com.aggregator.routing;

/** Thrown when the stored workflow definitions are invalid. */
public class InvalidWorkflowDefinitionException extends RuntimeException {

    public InvalidWorkflowDefinitionException(String message) {
        super(message);
    }
}
