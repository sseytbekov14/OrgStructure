package cs_orgchart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends requests for an alias host (APP_ALIAS_HOSTS, e.g. the short name without the domain) to the same address
 * on the canonical host (APP_CANONICAL_HOST), before Spring Security runs. The SAML Reply URL and the session
 * cookie belong to one host: a sign-in started on the alias would come back to the canonical host without its
 * session and end in "Sign-in did not complete". The scheme, the port given in Host, the path and the query stay.
 * Any other host (the canonical one, localhost, an IP address, no Host header) passes untouched; with
 * APP_CANONICAL_HOST or APP_ALIAS_HOSTS empty the filter does nothing.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CanonicalHostFilter extends OncePerRequestFilter {

    static final int DEFAULT_STATUS = HttpServletResponse.SC_FOUND;

    private final String canonicalHost;
    private final Set<String> aliasHosts;
    private final int status;

    public CanonicalHostFilter(@Value("${app.canonical-host:}") String canonicalHost,
                               @Value("${app.alias-hosts:}") String aliasHosts,
                               @Value("${app.alias-redirect-status:}") String status) {
        this.canonicalHost = canonicalHost == null ? "" : canonicalHost.trim().toLowerCase(Locale.ROOT);
        if (this.canonicalHost.contains("/") || this.canonicalHost.contains(":")) {
            throw new IllegalArgumentException("APP_CANONICAL_HOST must be a host name only, without scheme, port"
                    + " or path (e.g. orgchart.example.com)");
        }
        // The canonical host is never an alias of itself: that would redirect in a loop
        this.aliasHosts = aliasHosts == null ? Set.of() : Arrays.stream(aliasHosts.split(","))
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty() && !host.equals(this.canonicalHost))
                .collect(Collectors.toUnmodifiableSet());
        this.status = status(status);
        if (this.canonicalHost.isEmpty() != this.aliasHosts.isEmpty()) {
            log.warn("Alias host redirect is off: set both APP_CANONICAL_HOST and APP_ALIAS_HOSTS");
        }
    }

    private static int status(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_STATUS;
        }
        String trimmed = value.trim();
        if (trimmed.equals("301")) {
            return HttpServletResponse.SC_MOVED_PERMANENTLY;
        }
        if (trimmed.equals("302")) {
            return HttpServletResponse.SC_FOUND;
        }
        throw new IllegalArgumentException("APP_ALIAS_REDIRECT_STATUS must be 301 or 302, not " + trimmed);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String hostHeader = request.getHeader(HttpHeaders.HOST);
        if (canonicalHost.isEmpty() || aliasHosts.isEmpty() || hostHeader == null || hostHeader.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String trimmed = hostHeader.trim();
        // host[:port]; an IPv6 literal ([::1]:8080) has colons inside the brackets
        int colon = trimmed.lastIndexOf(':');
        boolean hasPort = colon > trimmed.lastIndexOf(']');
        String host = hasPort ? trimmed.substring(0, colon) : trimmed;
        if (!aliasHosts.contains(host.toLowerCase(Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }
        StringBuilder location = new StringBuilder(request.getScheme()).append("://").append(canonicalHost);
        if (hasPort) {
            location.append(trimmed.substring(colon));
        }
        location.append(request.getRequestURI());
        if (request.getQueryString() != null) {
            location.append('?').append(request.getQueryString());
        }
        // Only the host: the query may carry e-mail addresses
        log.debug("Alias host {} redirected to {}", host, canonicalHost);
        response.setStatus(status);
        response.setHeader(HttpHeaders.LOCATION, location.toString());
    }
}
