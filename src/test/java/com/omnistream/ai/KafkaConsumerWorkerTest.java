package com.omnistream.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnistream.ai.dto.ai.StructuredAnalysisResult.*;
import com.omnistream.ai.exception.ProcessingException;
import com.omnistream.ai.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaConsumerWorkerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EventProcessor processor = mock(EventProcessor.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final KafkaConsumerWorker worker = new KafkaConsumerWorker(json, processor);
    private final EventMessage event = new EventMessage(UUID.randomUUID(),"sensor","reading","sensor: W3",Map.of());

    private org.apache.kafka.clients.consumer.ConsumerRecord<String,String> record(String value) {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>(KafkaProducerService.RAW, 0, 1,
            event.eventId().toString(), value);
    }

    @Test void acknowledgesAfterProcessing() throws Exception {
        worker.receive(record(json.writeValueAsString(event)), ack);
        var order = inOrder(processor, ack);
        order.verify(processor).markProcessing(event.eventId());
        order.verify(processor).process(event);
        order.verify(ack).acknowledge();
    }

    @Test void letsListenerRetryOnProviderFailure() throws Exception {
        doThrow(new ProcessingException("provider unavailable")).when(processor).process(event);
        String payload = json.writeValueAsString(event);
        assertThatThrownBy(() -> worker.receive(record(payload), ack)).isInstanceOf(ProcessingException.class);
        verifyNoInteractions(ack);
    }

    @Test void malformedEnvelopeIsNeverAcknowledged() {
        assertThatThrownBy(() -> worker.receive(record("{"), ack)).isInstanceOf(ProcessingException.class);
        verifyNoInteractions(ack, processor);
    }

    @Test void localAdapterExtractsActualInputFields() {
        var result = new LocalParser().parse(event);
        assertThat(result.category()).isEqualTo(Category.IOT_TELEMETRY);
        assertThat(result.actionableEntities()).containsExactly(new Entity("sensor","W3"));
    }
}
