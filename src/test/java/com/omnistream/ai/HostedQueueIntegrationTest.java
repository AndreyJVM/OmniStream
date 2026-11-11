package com.omnistream.ai;

import com.omnistream.ai.service.AnalysisPort;
import com.omnistream.ai.service.LocalParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@ActiveProfiles("hosted")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "omnistream.api-key=hosted-test", "omnistream.parser-mode=local", "omnistream.relay-delay-ms=50"
})
class HostedQueueIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AnalysisPort parser;

    @Test void publicLandingAndWorkspaceLeaveTheLiveApiProtected() {
        assertThat(given().port(port).get("/").then().statusCode(200).extract().asString())
            .contains("Enter workspace");
        assertThat(given().port(port).get("/workspace").then().statusCode(200).extract().asString())
            .contains("Pipeline overview");
        given().port(port).get("/api/v1/events").then().statusCode(401);
        given().port(port).get("/actuator/health/readiness").then().statusCode(200);
    }

    @Test void processesQueuedEventsWithoutABroker() {
        when(parser.parse(any())).thenAnswer(call -> new LocalParser().parse(call.getArgument(0)));
        UUID id = submit();
        waitFor(id, "COMPLETED");
        assertThat(jdbc.queryForObject("select category from ingestion_events where id=?", String.class, id))
            .isEqualTo("FINANCIAL");
        verify(parser, times(1)).parse(any());
    }

    @Test void exhaustsRetriesAndRetainsTheFailedEvent() {
        when(parser.parse(any())).thenThrow(new IllegalStateException("Synthetic failure"));
        UUID id = submit();
        waitFor(id, "FAILED");
        verify(parser, times(4)).parse(any());
    }

    private UUID submit() {
        var response = given().port(port).contentType("application/json").header("X-API-KEY", "hosted-test")
            .body(Map.of("sourceId","hosted-test","eventType","invoice.received","rawPayload","Invoice: INV-21"))
            .post("/api/v1/events/ingest").then().statusCode(202).extract();
        return UUID.fromString(response.jsonPath().getString("eventId"));
    }

    private void waitFor(UUID id, String state) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select status from ingestion_events where id=?", String.class, id))
                .isEqualTo(state);
            assertThat(jdbc.queryForObject("select count(*) from ingestion_outbox where event_id=?", Integer.class, id))
                .isZero();
        });
    }
}
