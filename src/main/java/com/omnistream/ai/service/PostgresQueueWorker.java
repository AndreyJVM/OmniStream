package com.omnistream.ai.service;

import com.omnistream.ai.repository.IngestionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Hosted installations consume the same durable outbox directly from PostgreSQL. */
@Service
@Profile("hosted")
public class PostgresQueueWorker {
    private static final Logger log = LoggerFactory.getLogger(PostgresQueueWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IngestionEventRepository events;
    private final EventProcessor processor;

    public PostgresQueueWorker(JdbcTemplate jdbc, TransactionTemplate transactions,
                               IngestionEventRepository events, EventProcessor processor) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
        this.processor = processor;
    }

    private record Claim(EventMessage message, UUID token) {}

    @Scheduled(fixedDelayString = "${omnistream.relay-delay-ms}")
    public void poll() {
        var claim = transactions.execute(status -> claimNext());
        if (claim == null) return;
        try {
            processor.process(claim.message());
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> retryOrFail(claim));
            log.warn("Hosted processing attempt failed for {} ({})",
                claim.message().eventId(), failure.getClass().getSimpleName());
            return;
        }
        remove(claim);
    }

    private Claim claimNext() {
        UUID token = UUID.randomUUID();
        var ids = jdbc.queryForList("""
            WITH next AS (
                SELECT event_id FROM ingestion_outbox WHERE available_at <= now()
                ORDER BY available_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE ingestion_outbox o SET claim_token = ?, available_at = now() + interval '90 seconds'
            FROM next WHERE o.event_id = next.event_id RETURNING o.event_id
            """, UUID.class, token);
        if (ids.isEmpty()) return null;
        var event = events.lock(ids.getFirst()).orElseThrow();
        var claim = new Claim(EventMessage.from(event), token);
        if (event.getStatus().terminal()) {
            remove(claim);
            return null;
        }
        event.start();
        return claim;
    }

    private void retryOrFail(Claim claim) {
        UUID id = claim.message().eventId();
        var attempts = jdbc.queryForList("""
            SELECT failed_attempts FROM ingestion_outbox
            WHERE event_id = ? AND claim_token = ? FOR UPDATE
            """, Integer.class, id, claim.token());
        if (attempts.isEmpty()) return; // A new worker owns this expired lease.
        int count = attempts.getFirst() + 1;
        if (count >= 4) {
            var event = events.lock(id).orElseThrow();
            if (!event.getStatus().terminal()) event.fail("Processing retries exhausted.");
            remove(claim);
            return;
        }
        jdbc.update("""
            UPDATE ingestion_outbox SET failed_attempts = ?, claim_token = NULL,
                available_at = now() + (? * interval '1 second')
            WHERE event_id = ? AND claim_token = ?
            """, count, 1 << (count - 1), id, claim.token());
    }

    private void remove(Claim claim) {
        jdbc.update("DELETE FROM ingestion_outbox WHERE event_id = ? AND claim_token = ?",
            claim.message().eventId(), claim.token());
    }
}
