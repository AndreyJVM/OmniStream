package com.omnistream.ai.controller;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.actuate.health.*;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
public class HealthCheckController {
    @GetMapping("/health")
    public Map<String,String> health() { return Map.of("service", "omnistream-ai", "status", "alive"); }

    @org.springframework.context.annotation.Profile("!hosted")
    @Bean
    HealthIndicator kafkaHealthIndicator(Admin admin) {
        return () -> {
            try {
                var nodes = admin.describeCluster().nodes().get(3, TimeUnit.SECONDS);
                return nodes.isEmpty() ? Health.down().build() : Health.up().build();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Health.down().build();
            } catch (Exception ex) {
                return Health.down().build();
            }
        };
    }
}
