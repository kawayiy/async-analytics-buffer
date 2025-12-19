package com.patientcentre.analytics.config;

/** Defines what happens when the in-memory buffer reaches its maximum capacity. */
public enum DropPolicy {
    /** Drop the oldest events to make room for new ones. */
    DROP_OLDEST,
    /** Reject incoming events when full. */
    DROP_NEWEST,
    /** Fail fast by throwing an exception. */
    RAISE
}
