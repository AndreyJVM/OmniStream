package com.omnistream.ai;

import com.omnistream.ai.service.*;
import io.restassured.filter.cookie.CookieFilter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@ActiveProfiles("hosted")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "omnistream.public-workspace=true", "omnistream.api-key=hosted-test",
    "omnistream.parser-mode=groq", "omnistream.groq-key=fixture-key", "omnistream.relay-delay-ms=50"
})
class PublicWorkspaceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoPolicy demo;
    @MockitoBean AnalysisPort parser;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM demo_usage");
        when(parser.parse(any())).thenAnswer(call -> new LocalParser().parse(call.getArgument(0)));
    }

    private UUID submit(CookieFilter visitor) {
        var response = given().port(port).filter(visitor).contentType("application/json")
            .body(Map.of("sourceId","demo","eventType","invoice.received","rawPayload","Invoice: INV-21"))
            .post("/api/v1/events/ingest").then().statusCode(202).extract();
        return UUID.fromString(response.jsonPath().getString("eventId"));
    }

    @Test void visitorsCannotReadEachOthersPayloads() {
        var first = new CookieFilter();
        UUID id = submit(first);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT status FROM ingestion_events WHERE id=?", String.class, id))
                .isEqualTo("COMPLETED"));
        given().port(port).filter(first).get("/api/v1/events/" + id).then().statusCode(200);
        var second = new CookieFilter();
        given().port(port).filter(second).get("/api/v1/events/" + id).then().statusCode(404);
        assertThat(given().port(port).filter(second).get("/api/v1/events").jsonPath().getList("events")).isEmpty();
        given().port(port).get("/actuator/metrics").then().statusCode(401);
        given().port(port).header("X-API-KEY","hosted-test").get("/actuator/metrics").then().statusCode(200);
    }

    @Test void sessionQuotaRejectsFourthSubmission() {
        var visitor = new CookieFilter();
        for (int i = 0; i < 3; i++) submit(visitor);
        given().port(port).filter(visitor).contentType("application/json")
            .body(Map.of("sourceId","demo","eventType","invoice","rawPayload","Invoice: four"))
            .post("/api/v1/events/ingest").then().statusCode(429);
    }

    @Test void changingCookiesCannotBypassPeerQuota() {
        jdbc.update("INSERT INTO demo_usage(day,scope,requests) VALUES (CURRENT_DATE,'submissions:global',0)");
        // Populate the peer counter using a real visitor request, then exhaust it.
        submit(new CookieFilter());
        jdbc.update("UPDATE demo_usage SET requests=10 WHERE scope LIKE 'submissions:peer:%'");
        given().port(port).contentType("application/json")
            .body(Map.of("sourceId","demo","eventType","invoice","rawPayload","Invoice: other"))
            .post("/api/v1/events/ingest").then().statusCode(429);
    }

    @Test void oldUnownedEventsRemainPrivate() {
        var response = given().port(port).header("X-API-KEY","hosted-test").contentType("application/json")
            .body(Map.of("sourceId","private","eventType","invoice","rawPayload","Private invoice"))
            .post("/api/v1/events/ingest").then().statusCode(202).extract();
        given().port(port).get("/api/v1/events/" + response.jsonPath().getString("eventId")).then().statusCode(404);
    }

    @Test void budgetReservationsAreAtomicAndNeverRefunded() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 100; i++) requests.add(() -> {
                try { demo.reserveAiRequest("{}", 2048); return true; }
                catch (IllegalStateException exhausted) { return false; }
            });
            long accepted = 0;
            for (var future : pool.invokeAll(requests)) if (future.get()) accepted++;
            assertThat(accepted).isBetween(1L, 99L);
            assertThat(jdbc.queryForObject("SELECT reserved_usd FROM demo_usage WHERE scope='ai:global'",
                java.math.BigDecimal.class)).isLessThanOrEqualTo(java.math.BigDecimal.ONE);
            assertThatThrownBy(() -> demo.reserveAiRequest("{}",2048)).isInstanceOf(IllegalStateException.class);
        } finally { pool.shutdownNow(); }
    }

    @Test void cleanupRemovesExpiredVisitorData() {
        UUID id = submit(new CookieFilter());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_outbox WHERE event_id=?", Integer.class, id))
                .isZero());
        jdbc.update("UPDATE ingestion_events SET created_at=now()-interval '25 hours' WHERE id=?", id);
        demo.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_events WHERE id=?", Integer.class, id)).isZero();
    }
}
