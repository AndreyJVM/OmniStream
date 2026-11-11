package com.omnistream.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnistream.ai.dto.ai.StructuredAnalysisResult.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "groqLiveTest", matches = "true")
class GroqLiveContractTest {
    @Test void extractsAnInvoiceThroughGroq() throws Exception {
        String key = System.getenv("GROQ_API_KEY");
        assertThat(key != null && !key.isBlank()).as("GROQ_API_KEY is configured").isTrue();

        var parser = new ParserConfiguration().groq(key, "openai/gpt-oss-120b", new ObjectMapper());
        var event = new EventMessage(UUID.randomUUID(), "smoke-test", "invoice.received",
            "Invoice INV-42 for USD 120.00 is overdue.", Map.of());
        var result = parser.parse(event);

        assertThat(result.category()).isEqualTo(Category.FINANCIAL);
        assertThat(result.summary()).isNotBlank();
        assertThat(result.actionableEntities()).anyMatch(entity -> entity.value().contains("INV-42"));
    }
}
