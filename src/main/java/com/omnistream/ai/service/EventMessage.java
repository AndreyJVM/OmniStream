package com.omnistream.ai.service;

import com.omnistream.ai.model.IngestionEvent;
import java.util.Map;
import java.util.UUID;

public record EventMessage(UUID eventId, String sourceId, String eventType, String rawPayload, Map<String,String> metadata) {
    static EventMessage from(IngestionEvent event) {
        return new EventMessage(event.getId(), event.getSourceId(), event.getEventType(),
            event.getRawPayload(), event.getMetadata());
    }
}
