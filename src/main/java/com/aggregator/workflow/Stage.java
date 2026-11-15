package com.aggregator.workflow;

/** The two stages of a workflow. */
public enum Stage {
    SYNC, ASYNC;

    public String tag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
