package io.github.hemanthvk980.shortener.link.api;

import io.github.hemanthvk980.shortener.link.service.ClickRecorder;
import io.github.hemanthvk980.shortener.link.service.LinkNotFoundException;
import io.github.hemanthvk980.shortener.link.service.LinkService;
import io.github.hemanthvk980.shortener.link.service.LinkTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hot path. 302 (not 301) so browsers don't cache the redirect forever — links can expire or be
 * disabled, and every click should be counted. Analytics are recorded asynchronously.
 */
@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final LinkService service;
    private final ClickRecorder clicks;
    private final Clock clock;

    public RedirectController(LinkService service, ClickRecorder clicks, Clock clock) {
        this.service = service;
        this.clicks = clicks;
        this.clock = clock;
    }

    @Operation(summary = "Redirect to the target URL (302); 404 unknown, 410 expired/disabled")
    @GetMapping("/{code:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer) {
        if (code.length() < 4 || code.length() > 32) throw new LinkNotFoundException(code);
        LinkTarget target = service.resolveForRedirect(service.lookup(code));
        clicks.record(target.id(), referer, clock.instant());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target.targetUrl()))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer-when-downgrade")
                .build();
    }
}
