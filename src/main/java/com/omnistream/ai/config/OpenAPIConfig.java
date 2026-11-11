package com.omnistream.ai.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.*;

@Configuration
public class OpenAPIConfig {
    @Bean OpenAPI api() {
        return new OpenAPI().info(new Info().title("Omnistream Ingestion API").version("1.0.0"))
            .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
            .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-KEY")));
    }
}
