package com.omnistream.ai.service;

import com.omnistream.ai.dto.request.IngestionRequest;
import com.omnistream.ai.dto.response.IngestionResponse;
import com.omnistream.ai.model.IngestionEvent;
import com.omnistream.ai.service.port.EventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private final EventStore store;
    private final DemoPolicy demo;

    public IngestionService(EventStore store, DemoPolicy demo) { this.store = store; this.demo = demo; }

    public IngestionResponse accept(IngestionRequest request) {
        return accept(request, null, null);
    }

    @Transactional
    public IngestionResponse accept(IngestionRequest request, String owner, String client) {
        if (owner != null) demo.reserveSubmission(owner, client);
        var event = new IngestionEvent(request);
        event.setDemoOwner(owner);
        store.enqueue(event);
        return new IngestionResponse(event.getId(), "PENDING", event.getCreatedAt());
    }
}
