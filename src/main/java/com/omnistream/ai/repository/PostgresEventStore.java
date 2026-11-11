package com.omnistream.ai.repository;

import com.omnistream.ai.model.IngestionEvent;
import com.omnistream.ai.service.port.EventStore;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresEventStore implements EventStore {
    private final EntityManager entities;
    private final IngestionEventRepository events;
    private final JdbcTemplate jdbc;

    public PostgresEventStore(EntityManager entities, IngestionEventRepository events, JdbcTemplate jdbc) {
        this.entities = entities;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void enqueue(IngestionEvent event) {
        entities.persist(event);
        entities.flush();
        jdbc.update("INSERT INTO ingestion_outbox(event_id, created_at) VALUES (?, now())", event.getId());
    }

    @Override
    public Optional<IngestionEvent> lock(UUID id) {
        return events.lock(id);
    }
}
