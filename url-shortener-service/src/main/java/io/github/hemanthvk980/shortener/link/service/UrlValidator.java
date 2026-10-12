package io.github.hemanthvk980.shortener.link.service;

import io.github.hemanthvk980.shortener.config.AppProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates and normalises destination URLs. Threats addressed:
 * <ul>
 *   <li>XSS / script execution via {@code javascript:}, {@code data:}, {@code vbscript:} — only http(s);</li>
 *   <li>credential-confusion phishing ({@code https://bank.com@evil.example}) — userinfo rejected;</li>
 *   <li>pointing users at internal infrastructure — loopback/private/link-local IP literals and
 *       {@code localhost} rejected;</li>
 *   <li>redirect loops — links to this service's own host rejected.</li>
 * </ul>
 * Hostnames are NOT resolved via DNS (that would add latency and a DNS-rebinding false sense of
 * security; the service never fetches the URL itself).
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final int maxLength;
    private final Set<String> blockedHosts = new HashSet<>();

    public UrlValidator(AppProperties props) {
        this.maxLength = props.maxUrlLength();
        if (props.blockedHosts() != null) props.blockedHosts().forEach(h -> blockedHosts.add(h.toLowerCase(Locale.ROOT)));
        String own = URI.create(props.baseUrl()).getHost();
        if (own != null) blockedHosts.add(own.toLowerCase(Locale.ROOT));
    }

    public String validateAndNormalize(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidUrlException("url must not be blank");
        String candidate = raw.strip();
        if (candidate.length() > maxLength) throw new InvalidUrlException("url exceeds " + maxLength + " characters");
        if (candidate.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new InvalidUrlException("url contains control characters");

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("url is not well-formed");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) throw new InvalidUrlException("only http and https urls are allowed");
        if (uri.getRawUserInfo() != null) throw new InvalidUrlException("urls with embedded credentials are not allowed");

        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new InvalidUrlException("url must have a host");
        // java.net.URI only yields a host for ASCII (or already punycoded) names, so this is ASCII.
        String asciiHost = host.toLowerCase(Locale.ROOT);
        rejectInternalHost(asciiHost);
        if (blockedHosts.contains(asciiHost) || blockedHosts.stream().anyMatch(b -> asciiHost.endsWith("." + b))) {
            throw new InvalidUrlException("destination host is not allowed");
        }

        // Rebuilt from raw components so existing percent-encoding is preserved exactly.
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scheme + "://" + asciiHost + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "")
                + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
    }

    private static void rejectInternalHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new InvalidUrlException("internal hosts are not allowed");
        }
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean isIpLiteral = IPV4.matcher(literal).matches() || literal.contains(":");
        if (!isIpLiteral) return;
        try {
            InetAddress addr = InetAddress.getByName(literal); // literal → no DNS lookup
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isUniqueLocalV6(addr)) {
                throw new InvalidUrlException("private or loopback addresses are not allowed");
            }
        } catch (UnknownHostException e) {
            throw new InvalidUrlException("invalid IP address");
        }
    }

    private static boolean isUniqueLocalV6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc; // fc00::/7
    }
}
