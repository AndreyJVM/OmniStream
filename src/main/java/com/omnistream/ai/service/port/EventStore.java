package com.omnistream.ai.service.port;

import com.omnistream.ai.model.IngestionEvent;
import java.util.Optional;
import java.util.UUID;

public interface EventStore {
    /** Commit the received event and its pending publication as one unit. */
    void enqueue(IngestionEvent event);

    /** Lock one event for the enclosing processing transaction. */
    Optional<IngestionEvent> lock(UUID id);
}
