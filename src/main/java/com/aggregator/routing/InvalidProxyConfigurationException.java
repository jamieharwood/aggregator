package com.aggregator.routing;

/** Thrown at startup when the {@code proxy.*} configuration is invalid. */
public class InvalidProxyConfigurationException extends RuntimeException {

    public InvalidProxyConfigurationException(String message) {
        super(message);
    }
}
