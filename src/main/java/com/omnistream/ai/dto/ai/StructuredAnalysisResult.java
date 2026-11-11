package com.omnistream.ai.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record StructuredAnalysisResult(
    String summary,
    Category category,
    @JsonProperty("sentiment_score") BigDecimal sentimentScore,
    @JsonProperty("risk_level") Risk riskLevel,
    @JsonProperty("actionable_entities") List<Entity> actionableEntities
) {
    public enum Category { FINANCIAL, SYSTEM_LOG, IOT_TELEMETRY, UNKNOWN }
    public enum Risk { LOW, MEDIUM, HIGH, CRITICAL }
    public record Entity(String key, String value) {
        public Entity {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("Entity key and value are required");
            }
        }
    }
    public StructuredAnalysisResult {
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("Summary is required");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(riskLevel, "riskLevel");
        if (sentimentScore == null || sentimentScore.abs().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Sentiment must be between -1 and 1");
        }
        actionableEntities = List.copyOf(actionableEntities);
    }
}
