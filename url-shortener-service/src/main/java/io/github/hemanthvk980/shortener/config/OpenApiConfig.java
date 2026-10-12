package io.github.hemanthvk980.shortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shortenerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("URL Shortener API")
                .version("v1")
                .description("Create short links, redirect, and read click analytics. Errors use RFC 7807 problem details.")
                .license(new License().name("MIT")));
    }
}
