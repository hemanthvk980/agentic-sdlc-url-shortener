package io.github.hemanthvk980.shortener.link.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateLinkRequest(
        @Schema(example = "https://example.com/articles/2026/agentic-sdlc?ref=newsletter")
        @NotBlank @Size(max = 2048) String url,

        @Schema(description = "Optional custom code (4-32 chars: letters, digits, '-', '_')", example = "my-talk")
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$", message = "must be 4-32 characters of letters, digits, '-' or '_'")
        String alias,

        @Schema(description = "Optional expiry (ISO-8601 instant, must be in the future)", example = "2026-12-31T23:59:59Z")
        Instant expiresAt) {
}
