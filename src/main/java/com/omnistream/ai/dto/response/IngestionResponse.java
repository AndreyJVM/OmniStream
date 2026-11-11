package com.omnistream.ai.dto.response;

import java.time.Instant;
import java.util.UUID;

public record IngestionResponse(UUID eventId, String status, Instant timestamp) {}
