package com.omnistream.ai.model;

public enum EventStatus {
    RECEIVED, PROCESSING, COMPLETED, FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
