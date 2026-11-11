package com.omnistream.ai.service;

import org.springframework.context.annotation.Profile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnistream.ai.exception.ProcessingException;
import com.omnistream.ai.repository.IngestionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.concurrent.*;

@Profile("!hosted")
@Service
public class KafkaProducerService {
    public static final String RAW = "raw-ingestion-events";
    public static final String DLQ = RAW + ".DLQ";
    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);
    private final JdbcTemplate jdbc;
    private final IngestionEventRepository events;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper json;

    public KafkaProducerService(JdbcTemplate jdbc, IngestionEventRepository events,
                                KafkaTemplate<String,String> kafka, ObjectMapper json) {
        this.jdbc = jdbc;
        this.events = events;
        this.kafka = kafka;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${omnistream.relay-delay-ms}")
    @Transactional
    public void publishPending() throws JsonProcessingException {
        var ids = jdbc.queryForList("""
            SELECT event_id FROM ingestion_outbox ORDER BY created_at, event_id
            LIMIT 64 FOR UPDATE SKIP LOCKED
            """, UUID.class);
        if (ids.isEmpty()) return;
        var batch = events.findAllById(ids);
        var sent = new java.util.ArrayList<CompletableFuture<?>>(batch.size());
        for (var event : batch) {
            sent.add(kafka.send(RAW, event.getId().toString(), json.writeValueAsString(EventMessage.from(event))));
        }
        try {
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException("Outbox relay interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ProcessingException("Kafka did not confirm the outbox batch", e);
        }
        jdbc.batchUpdate("DELETE FROM ingestion_outbox WHERE event_id = ?", batch, batch.size(),
            (statement, event) -> statement.setObject(1, event.getId()));
        log.debug("Published {} ingestion events", batch.size());
    }
}
