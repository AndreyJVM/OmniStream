package com.omnistream.ai.model;

import com.omnistream.ai.dto.ai.StructuredAnalysisResult;
import com.omnistream.ai.dto.request.IngestionRequest;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "ingestion_events")
public class IngestionEvent {
    @Id private UUID id;
    private String sourceId;
    private String demoOwner;
    private String eventType;
    @Column(columnDefinition = "text") private String rawPayload;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String,String> metadata;
    @Enumerated(EnumType.STRING) private EventStatus status;
    private String category;
    private String riskLevel;
    @Column(precision = 3, scale = 2) private BigDecimal sentimentScore;
    @JdbcTypeCode(SqlTypes.JSON) private StructuredAnalysisResult extractedData;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    protected IngestionEvent() {}

    public IngestionEvent(IngestionRequest request) {
        id = UUID.randomUUID();
        sourceId = request.sourceId();
        eventType = request.eventType();
        rawPayload = request.rawPayload();
        metadata = request.metadata() == null ? Map.of() : Map.copyOf(request.metadata());
        createdAt = updatedAt = Instant.now();
        status = EventStatus.RECEIVED;
    }

    public void start() {
        status = EventStatus.PROCESSING;
        updatedAt = Instant.now();
    }

    public void complete(StructuredAnalysisResult result) {
        extractedData = result;
        category = result.category().name();
        riskLevel = result.riskLevel().name();
        sentimentScore = result.sentimentScore();
        status = EventStatus.COMPLETED;
        updatedAt = Instant.now();
    }

    public void fail(String reason) {
        status = EventStatus.FAILED;
        errorMessage = reason;
        updatedAt = Instant.now();
    }

    public void setDemoOwner(String owner) { demoOwner = owner; }
    public UUID getId() { return id; }
    public String getSourceId() { return sourceId; }
    public String getEventType() { return eventType; }
    public String getRawPayload() { return rawPayload; }
    public Map<String,String> getMetadata() { return metadata; }
    public EventStatus getStatus() { return status; }
    public StructuredAnalysisResult getExtractedData() { return extractedData; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
