package io.github.hemanthvk980.shortener.link.api;

import io.github.hemanthvk980.shortener.link.domain.Link;
import io.github.hemanthvk980.shortener.link.service.CreateLinkCommand;
import io.github.hemanthvk980.shortener.link.service.LinkService;
import io.github.hemanthvk980.shortener.link.service.LinkStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/links")
@Tag(name = "Links")
public class LinkController {

    private final LinkService service;

    public LinkController(LinkService service) {
        this.service = service;
    }

    @Operation(summary = "Create a short link",
            description = "Send an Idempotency-Key header to make retries safe: the same key returns the original link.")
    @PostMapping
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest req,
            @Parameter(description = "Client-generated unique key (max 64 chars)")
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey) {
        Link link = service.create(new CreateLinkCommand(req.url(), req.alias(), req.expiresAt(), idempotencyKey));
        String shortUrl = service.shortUrl(link.getCode());
        return ResponseEntity.created(URI.create("/api/v1/links/" + link.getCode()))
                .body(LinkResponse.of(link, shortUrl));
    }

    @Operation(summary = "Get link metadata")
    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$") String code) {
        Link link = service.get(code);
        return LinkResponse.of(link, service.shortUrl(code));
    }

    @Operation(summary = "Click analytics: total, per day, top referrers")
    @GetMapping("/{code}/stats")
    public LinkStats stats(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$") String code) {
        return service.stats(code);
    }

    @Operation(summary = "Disable a link (admin; requires X-API-Key). Analytics are retained.")
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> disable(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$") String code,
                                        @RequestHeader(name = "X-API-Key", required = false) String apiKey) {
        service.disable(code, apiKey);
        return ResponseEntity.noContent().build();
    }
}
