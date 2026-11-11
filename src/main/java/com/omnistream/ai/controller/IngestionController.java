package com.omnistream.ai.controller;

import com.omnistream.ai.dto.request.IngestionRequest;
import com.omnistream.ai.dto.response.IngestionResponse;
import com.omnistream.ai.model.IngestionEvent;
import com.omnistream.ai.dto.response.EventSummary;
import com.omnistream.ai.repository.IngestionEventRepository;
import com.omnistream.ai.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events")
@SecurityRequirement(name = "apiKey")
public class IngestionController {
    private final IngestionService service;
    private final IngestionEventRepository repository;
    private final String parserMode;
    private final String parserModel;

    public IngestionController(IngestionService service, IngestionEventRepository repository,
                               @Value("${omnistream.parser-mode}") String parserMode,
                               @Value("${omnistream.groq-model}") String groqModel,
                               @Value("${omnistream.openai-model}") String openaiModel) {
        this.service = service;
        this.repository = repository;
        this.parserMode = parserMode;
        this.parserModel = switch (parserMode) {
            case "groq" -> groqModel;
            case "openai" -> openaiModel;
            default -> "rules";
        };
    }

    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Accept an event for asynchronous analysis")
    @ApiResponse(responseCode = "202", description = "Event and outbox record committed")
    public ResponseEntity<IngestionResponse> ingest(@Valid @RequestBody IngestionRequest request) {
        var result = service.accept(request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/events/" + result.eventId())).body(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Inspect an event and its extracted data")
    public IngestionEvent event(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    public record Feed(List<EventSummary> events, String parserMode, String parserModel) {}

    @GetMapping
    @Operation(summary = "List the 100 most recent events")
    public Feed recent() {
        var page = PageRequest.of(0, 100);
        return new Feed(repository.recent(page), parserMode, parserModel);
    }
}
