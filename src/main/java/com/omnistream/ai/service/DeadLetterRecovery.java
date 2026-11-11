package com.omnistream.ai.service;

import org.springframework.context.annotation.Profile;
import com.omnistream.ai.model.IngestionEvent;
import com.omnistream.ai.repository.IngestionEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Profile("!hosted")
@Service
public class DeadLetterRecovery {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecovery.class);
    private final IngestionEventRepository events;
    private final DeadLetterPublishingRecoverer publisher;

    public DeadLetterRecovery(IngestionEventRepository events, KafkaTemplate<String,String> kafka) {
        this.events = events;
        publisher = new DeadLetterPublishingRecoverer(kafka,
            (record, failure) -> new TopicPartition(KafkaProducerService.DLQ, record.partition()));
        publisher.setFailIfSendResultIsError(true);
    }

    @Transactional
    public void recover(ConsumerRecord<?,?> record, Exception failure) {
        Optional<IngestionEvent> event = Optional.empty();
        if (record.key() instanceof String key) {
            try {
                event = events.lock(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // Unassociated malformed records still belong in the DLQ.
            }
        }
        if (event.isPresent() && event.get().getStatus().terminal()) return;
        publisher.accept(record, failure);
        event.ifPresent(row -> row.fail("Processing retries exhausted. Inspect the dead-letter topic."));
        log.warn("Recovered ingestion record partition={} offset={} linked={}",
            record.partition(), record.offset(), event.isPresent());
    }
}
