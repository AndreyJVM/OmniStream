package com.omnistream.ai.service;

import com.omnistream.ai.exception.ProcessingException;
import com.omnistream.ai.service.port.EventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class EventProcessor {
    private final EventStore events;
    private final AnalysisPort analysis;

    public EventProcessor(EventStore events, AnalysisPort analysis) {
        this.events = events;
        this.analysis = analysis;
    }

    @Transactional
    public void markProcessing(UUID id) {
        var event = events.lock(id).orElseThrow(() -> new ProcessingException("Event does not exist"));
        if (!event.getStatus().terminal()) event.start();
    }

    @Transactional
    public void process(EventMessage message) {
        // Serialize redeliveries of this ID across replicas; other event IDs remain independent.
        var event = events.lock(message.eventId()).orElseThrow(() -> new ProcessingException("Event does not exist"));
        if (!event.getStatus().terminal()) event.complete(analysis.parse(message));
    }
}
