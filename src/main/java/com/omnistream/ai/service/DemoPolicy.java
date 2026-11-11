package com.omnistream.ai.service;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class DemoPolicy {
    public static final String OWNER = "omnistream.demoOwner";
    public static final String CLIENT = "omnistream.demoClient";
    private static final String COOKIE = "omnistream-visitor";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final boolean enabled;
    private final String secret;
    private final String mode;
    private final String model;
    private final BigDecimal dailyBudget;
    private final BigDecimal tokenRate;
    private final SecureRandom random = new SecureRandom();

    public DemoPolicy(JdbcTemplate jdbc, PlatformTransactionManager manager,
        @Value("${omnistream.public-workspace:false}") boolean enabled,
        @Value("${omnistream.api-key}") String secret,
        @Value("${omnistream.parser-mode}") String mode,
        @Value("${omnistream.groq-model}") String model,
        @Value("${omnistream.demo.daily-budget-usd:1.00}") BigDecimal dailyBudget,
        @Value("${omnistream.demo.max-token-usd-per-million:5.00}") BigDecimal tokenRate) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
        this.secret = secret;
        this.mode = mode;
        this.model = model;
        this.dailyBudget = dailyBudget;
        this.tokenRate = tokenRate;
        if (dailyBudget.signum() < 0 || tokenRate.signum() <= 0) throw new IllegalArgumentException("Invalid demo budget");
    }

    public void identify(HttpServletRequest request, HttpServletResponse response) {
        String token = null;
        if (request.getCookies() != null) {
            token = Arrays.stream(request.getCookies()).filter(c -> COOKIE.equals(c.getName()))
                .map(Cookie::getValue).filter(v -> v.matches("[0-9a-f]{64}")).findFirst().orElse(null);
        }
        if (token == null) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            token = HexFormat.of().formatHex(bytes);
            var cookie = ResponseCookie.from(COOKIE, token).httpOnly(true).sameSite("Strict")
                .secure(request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
                .path("/").maxAge(Duration.ofHours(24)).build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        request.setAttribute(OWNER, hash(token));
        // Socket peer is intentional. Proxy-sharing clients share this additional quota.
        request.setAttribute(CLIENT, hash(request.getRemoteAddr()));
    }

    private String hash(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }

    /** Joins intake's transaction so rejected or rolled-back events consume no submission quota. */
    public void reserveSubmission(String owner, String client) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        increment(day, "submissions:global", 20);
        increment(day, "submissions:peer:" + client, 10);
        increment(day, "submissions:visitor:" + owner, 3);
    }

    private void increment(LocalDate day, String scope, int maximum) {
        jdbc.update("INSERT INTO demo_usage(day, scope) VALUES (?, ?) ON CONFLICT DO NOTHING", day, scope);
        int updated = jdbc.update("""
            UPDATE demo_usage SET requests = requests + 1
            WHERE day = ? AND scope = ? AND requests < ?
            """, day, scope, maximum);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "Demo submission quota reached. Try again after midnight UTC.");
    }

    /** Commits before every provider attempt, even when the enclosing processing transaction rolls back. */
    public void reserveAiRequest(String body, int completionTokens) {
        if (!enabled) return;
        if (!"groq".equals(mode) || !"openai/gpt-oss-120b".equals(model)) {
            throw new IllegalStateException("Public AI demo requires the budgeted Groq model");
        }
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 16384 || completionTokens > 2048 || completionTokens < 1) {
            throw new IllegalArgumentException("Demo provider request exceeds its token bound");
        }
        // UTF-8 byte count bounds text tokens. Extra allowance covers chat framing.
        BigDecimal reservation = BigDecimal.valueOf((long) bytes + 4096 + completionTokens)
            .multiply(tokenRate).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.UP);
        independent.executeWithoutResult(status -> {
            var day = LocalDate.now(ZoneOffset.UTC);
            jdbc.update("INSERT INTO demo_usage(day, scope) VALUES (?, 'ai:global') ON CONFLICT DO NOTHING", day);
            int updated = jdbc.update("""
                UPDATE demo_usage SET reserved_usd = reserved_usd + ?
                WHERE day = ? AND scope = 'ai:global' AND reserved_usd + ? <= ?
                """, reservation, day, reservation, dailyBudget);
            if (updated == 0) throw new IllegalStateException("Daily AI demo budget exhausted");
        });
    }

    @Scheduled(fixedDelay = 300000)
    public void cleanup() {
        if (!enabled) return;
        independent.executeWithoutResult(status -> {
            // Lock queue rows first, following the worker's lock order. Skip active claims.
            var ids = jdbc.queryForList("""
                SELECT e.id FROM ingestion_events e JOIN ingestion_outbox o ON o.event_id = e.id
                WHERE e.demo_owner IS NOT NULL AND e.created_at < now() - interval '24 hours'
                AND (o.claim_token IS NULL OR o.available_at <= now())
                LIMIT 100 FOR UPDATE OF o SKIP LOCKED
                """, UUID.class);
            for (var id : ids) {
                jdbc.update("DELETE FROM ingestion_outbox WHERE event_id = ?", id);
                jdbc.update("DELETE FROM ingestion_events WHERE id = ?", id);
            }
            jdbc.update("""
                DELETE FROM ingestion_events e WHERE demo_owner IS NOT NULL
                AND created_at < now() - interval '24 hours'
                AND NOT EXISTS (SELECT 1 FROM ingestion_outbox o WHERE o.event_id = e.id)
                """);
            jdbc.update("DELETE FROM demo_usage WHERE day < ?", LocalDate.now(ZoneOffset.UTC).minusDays(2));
        });
    }
}
