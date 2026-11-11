package com.omnistream.ai.service;

import com.fasterxml.jackson.databind.*;
import com.omnistream.ai.dto.ai.StructuredAnalysisResult.Category;
import com.omnistream.ai.exception.ProcessingException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class LLMAgenticParserTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> captured = new AtomicReference<>();
    private final EventMessage event = new EventMessage(UUID.randomUUID(),"ledger","invoice.received","invoice: INV-7",Map.of());
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private LLMAgenticParser parser;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        parser = ParserConfiguration.groqParser("test-key", "openai/gpt-oss-120b", json,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/parse"));
    }

    @AfterEach void close() { server.stop(0); }

    private void reply(int status, String content, String finishReason) throws Exception {
        var message = json.createObjectNode().put("role","assistant").put("content",content);
        message.putNull("refusal");
        byte[] bytes = json.writeValueAsString(Map.of("choices",List.of(Map.of(
            "finish_reason",finishReason,"message",message)))).getBytes(StandardCharsets.UTF_8);
        server.createContext("/parse", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                captured.set(json.readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(status,bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    @Test void sendsStrictSchemaAndDecodesAnalysis() throws Exception {
        reply(200,"""
            {"summary":"Invoice INV-7 received.","category":"FINANCIAL","risk_level":"LOW",
             "sentiment_score":0,"actionable_entities":[{"key":"invoice","value":"INV-7"}]}
            ""","stop");
        var result = parser.parse(event);
        assertThat(result.category()).isEqualTo(Category.FINANCIAL);
        assertThat(result.actionableEntities().getFirst().value()).isEqualTo("INV-7");
        var request = captured.get();
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(request.path("temperature").intValue()).isEqualTo(1);
        assertThat(request.path("top_p").intValue()).isEqualTo(1);
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("medium");
        assertThat(request.path("max_completion_tokens").intValue()).isEqualTo(2048);
        assertThat(request.path("stream").isBoolean()).isTrue();
        assertThat(request.path("stream").booleanValue()).isFalse();
        assertThat(request.has("store")).isFalse();
        assertThat(request.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(request.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(request.at("/response_format/json_schema/schema/additionalProperties").asBoolean()).isFalse();
        assertThat(request.at("/messages/1/content").asText()).contains(event.rawPayload());
    }

    @Test void rejectsTruncation() throws Exception {
        reply(200,"{}","length");
        assertThatThrownBy(() -> parser.parse(event)).isInstanceOf(ProcessingException.class);
    }

    @Test void rejectsOutOfRangeSentiment() throws Exception {
        reply(200,"""
            {"summary":"Invoice","category":"FINANCIAL","risk_level":"LOW","sentiment_score":8,"actionable_entities":[]}
            ""","stop");
        assertThatThrownBy(() -> parser.parse(event)).isInstanceOf(ProcessingException.class);
    }

    @Test void providerFailureCarriesStatusOnly() throws Exception {
        reply(429,"sensitive upstream text","stop");
        assertThatThrownBy(() -> parser.parse(event)).isInstanceOf(ProcessingException.class)
            .hasMessage("Analysis provider returned HTTP 429");
    }
}
