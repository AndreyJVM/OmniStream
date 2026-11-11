package com.omnistream.ai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.Map;

public record IngestionRequest(
    @NotBlank @Size(max = 100) @Schema(example = "ledger-api") String sourceId,
    @NotBlank @Size(max = 100) @Schema(example = "invoice.received") String eventType,
    @NotBlank @Size(max = 65536) @Schema(example = "Invoice INV-1098 for USD 240 is due Friday.") String rawPayload,
    @Size(max = 32) Map<@NotBlank @Size(max = 100) String, @NotNull @Size(max = 512) String> metadata
) {}
