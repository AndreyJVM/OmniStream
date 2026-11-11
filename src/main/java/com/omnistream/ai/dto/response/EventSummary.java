package com.omnistream.ai.dto.response;

import com.omnistream.ai.model.EventStatus;
import java.time.Instant;
import java.util.UUID;

public record EventSummary(UUID id, String sourceId, String eventType, EventStatus status,
                           String category, Instant createdAt, Instant updatedAt) {}
