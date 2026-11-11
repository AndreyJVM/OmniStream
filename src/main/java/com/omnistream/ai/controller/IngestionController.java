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
import jakarta.servlet.http.HttpServletRequest;
import com.omnistream.ai.service.DemoPolicy;

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
    public ResponseEntity<IngestionResponse> ingest(@Valid @RequestBody IngestionRequest request, HttpServletRequest http) {
        String owner = (String) http.getAttribute(DemoPolicy.OWNER);
        if (owner != null && (request.rawPayload().length() > 2000 ||
            (request.metadata() != null && !request.metadata().isEmpty()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The public demo accepts up to 2,000 characters and no metadata. Use the supplied examples.");
        }
        var result = service.accept(request, owner, (String) http.getAttribute(DemoPolicy.CLIENT));
        return ResponseEntity.accepted().location(URI.create("/api/v1/events/" + result.eventId())).body(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Inspect an event and its extracted data")
    public IngestionEvent event(@PathVariable UUID id, HttpServletRequest request) {
        String owner = (String) request.getAttribute(DemoPolicy.OWNER);
        return (owner == null ? repository.findById(id) : repository.findByIdAndDemoOwner(id, owner)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    public record Feed(List<EventSummary> events, String parserMode, String parserModel) {}

    @GetMapping
    @Operation(summary = "List the 100 most recent events")
    public Feed recent(HttpServletRequest request) {
        var page = PageRequest.of(0, 100);
        String owner = (String) request.getAttribute(DemoPolicy.OWNER);
        return new Feed(owner == null ? repository.recent(page) : repository.recentForOwner(owner, page), parserMode, parserModel);
    }
}
