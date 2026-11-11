package com.omnistream.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class ParserConfiguration {
    @Bean
    @ConditionalOnProperty(name = "omnistream.parser-mode", havingValue = "groq")
    LLMAgenticParser groq(@Value("${omnistream.groq-key}") String key,
                          @Value("${omnistream.groq-model}") String model, ObjectMapper json, DemoPolicy demo) throws IOException {
        return groqParser(key, model, json, URI.create("https://api.groq.com/openai/v1/chat/completions"), demo::reserveAiRequest);
    }

    static LLMAgenticParser groqParser(String key, String model, ObjectMapper json, URI endpoint) throws IOException {
        return groqParser(key, model, json, endpoint, (body, tokens) -> {});
    }

    static LLMAgenticParser groqParser(String key, String model, ObjectMapper json, URI endpoint,
        java.util.function.BiConsumer<String,Integer> budget) throws IOException {
        if (key.isBlank()) throw new IllegalArgumentException("GROQ_API_KEY is required for groq mode");
        return new LLMAgenticParser(key, model, json, endpoint,
            Map.of("temperature", 1, "top_p", 1, "reasoning_effort", "medium",
                "max_completion_tokens", 2048, "stream", false), budget);
    }

    @Bean
    @ConditionalOnProperty(name = "omnistream.parser-mode", havingValue = "openai")
    LLMAgenticParser openai(@Value("${omnistream.openai-key}") String key,
                            @Value("${omnistream.openai-model}") String model, ObjectMapper json, DemoPolicy demo) throws IOException {
        if (key.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY is required for openai mode");
        return new LLMAgenticParser(key, model, json, URI.create("https://api.openai.com/v1/chat/completions"),
            Map.of("store", false, "max_completion_tokens", 1800), demo::reserveAiRequest);
    }
}
