package com.omnistream.ai.service;

import com.omnistream.ai.dto.request.IngestionRequest;
import com.omnistream.ai.dto.response.IngestionResponse;
import com.omnistream.ai.model.IngestionEvent;
import com.omnistream.ai.service.port.EventStore;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {
    private final EventStore store;

    public IngestionService(EventStore store) { this.store = store; }

    public IngestionResponse accept(IngestionRequest request) {
        var event = new IngestionEvent(request);
        store.enqueue(event);
        return new IngestionResponse(event.getId(), "PENDING", event.getCreatedAt());
    }
}
