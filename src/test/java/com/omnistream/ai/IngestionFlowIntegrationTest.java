package com.omnistream.ai;

import com.omnistream.ai.dto.ai.StructuredAnalysisResult;
import com.omnistream.ai.dto.ai.StructuredAnalysisResult.*;
import com.omnistream.ai.service.*;
import io.restassured.specification.RequestSpecification;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionFlowIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.apache.kafka.clients.admin.Admin admin;
    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}") String brokers;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<String,String> producer;
    @MockitoBean AnalysisPort analysis;
    private static final String BODY = """
        {"sourceId":"sensor-west","eventType":"temperature.warning","rawPayload":"sensor: W3 temperature: 95",
         "metadata":{"region":"west"}}
        """;
    private static final StructuredAnalysisResult RESULT = new StructuredAnalysisResult("Sensor W3 is hot.",
        Category.IOT_TELEMETRY, new BigDecimal("-0.2"), Risk.MEDIUM, List.of(new Entity("sensor","W3")));

    RequestSpecification api() {
        return given().port(port).header("X-API-KEY", "integration-test-key").contentType("application/json");
    }

    @BeforeEach void prepare() {
        when(analysis.parse(any())).thenReturn(RESULT);
    }

    @AfterEach void pauseWorker() {
        listeners.stop();
    }

    private UUID submit() {
        var response = api().body(BODY).post("/api/v1/events/ingest");
        response.then().statusCode(202);
        assertThat(response.jsonPath().getString("status")).isEqualTo("PENDING");
        var id = UUID.fromString(response.jsonPath().getString("eventId"));
        assertThat(response.header("Location")).endsWith(id.toString());
        return id;
    }

    @Test void acceptsDurablyBeforeWorkerStarts() {
        UUID id = submit();
        assertThat(status(id)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("select metadata->>'region' from ingestion_events where id=?", String.class, id))
            .isEqualTo("west");
    }

    @Test void consumesAnalysesAndPersistsJsonbOncePerTerminalEvent() throws Exception {
        UUID id = submit();
        listeners.start();
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(status(id)).isEqualTo("COMPLETED"));
        assertThat(jdbc.queryForObject("""
            select extracted_data->'actionable_entities'->0->>'value' from ingestion_events where id=?
            """, String.class, id)).isEqualTo("W3");
        var duplicate = new EventMessage(id,"sensor-west","temperature.warning","sensor: W3",Map.of());
        var sent = producer.send(KafkaProducerService.RAW, id.toString(),
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(duplicate)).get(10, TimeUnit.SECONDS);
        var position = sent.getRecordMetadata();
        var partition = new org.apache.kafka.common.TopicPartition(position.topic(), position.partition());
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            var offsets = admin.listConsumerGroupOffsets("omnistream-processing-group")
                .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            return offsets.containsKey(partition) && offsets.get(partition).offset() > position.offset();
        });
        verify(analysis, times(1)).parse(argThat(e -> e != null && id.equals(e.eventId())));
    }

    @Test void exhaustedRetriesPublishDlqAndMarkFailed() {
        UUID id = submit();
        when(analysis.parse(argThat(e -> e != null && id.equals(e.eventId()))))
            .thenThrow(new IllegalStateException("simulated provider outage"));
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + id);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaProducerService.DLQ));
            listeners.start();
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(status(id)).isEqualTo("FAILED"));
            await().atMost(Duration.ofSeconds(20)).until(() ->
                java.util.stream.StreamSupport.stream(consumer.poll(Duration.ofMillis(500)).spliterator(), false)
                    .anyMatch(record -> id.toString().equals(record.key())));
        }
        verify(analysis, times(4)).parse(argThat(e -> e != null && id.equals(e.eventId())));
    }

    @Test void rejectsMissingKeyAndInvalidPayload() {
        given().port(port).contentType("application/json").body(BODY)
            .post("/api/v1/events/ingest").then().statusCode(401);
        api().body("{}").post("/api/v1/events/ingest").then().statusCode(400);
    }

    @Test @EnabledIfSystemProperty(named = "latencyCheck", matches = "true")
    void warmLoopbackIntakeBudget() throws Exception {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events/ingest"))
            .header("X-API-KEY","integration-test-key").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        var samples = new ArrayList<Long>();
        for (int i = 0; i < 22; i++) {
            long start = System.nanoTime();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.nanoTime() - start;
            assertThat(response.statusCode()).isEqualTo(202);
            if (i >= 2) samples.add(elapsed);
        }
        Collections.sort(samples);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Warm intake median={}ms p95={}ms",
            samples.get(10) / 1_000_000.0, samples.get(18) / 1_000_000.0);
        assertThat(samples.get(10)).isLessThan(Duration.ofMillis(30).toNanos());
        assertThat(samples.get(18)).isLessThan(Duration.ofMillis(50).toNanos());
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from ingestion_events where id=?", String.class, id);
    }
}
