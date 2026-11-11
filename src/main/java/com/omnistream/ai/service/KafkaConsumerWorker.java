package com.omnistream.ai.service;

import org.springframework.context.annotation.Profile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnistream.ai.exception.ProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Profile("!hosted")
@Component
public class KafkaConsumerWorker {
    private final ObjectMapper json;
    private final EventProcessor processor;

    public KafkaConsumerWorker(ObjectMapper json, EventProcessor processor) {
        this.json = json;
        this.processor = processor;
    }

    @KafkaListener(id = "ingestion-worker", idIsGroup = false, topics = KafkaProducerService.RAW)
    public void receive(ConsumerRecord<String,String> record, Acknowledgment acknowledgment) {
        try {
            var message = json.readValue(record.value(), EventMessage.class);
            if (message == null || message.eventId() == null || message.rawPayload() == null ||
                message.rawPayload().isBlank() || message.eventType() == null || message.sourceId() == null) {
                throw new ProcessingException("Event envelope is missing required fields");
            }
            if (!message.eventId().toString().equals(record.key())) {
                throw new ProcessingException("Event ID does not match the Kafka key");
            }
            processor.markProcessing(message.eventId());
            processor.process(message);
            acknowledgment.acknowledge();
        } catch (JsonProcessingException e) {
            throw new ProcessingException("Malformed event envelope", e);
        }
    }
}
