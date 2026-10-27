package cs_orgchart.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalHostFilterTest {

    private static final String CANONICAL = "ccaorgchart.go1.kworld.kpmg.com";
    private static final String ALIAS = "ccaorgchart";

    private final CanonicalHostFilter filter = new CanonicalHostFilter(CANONICAL, ALIAS, "");

    private static MockHttpServletRequest request(String host, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setScheme("https");
        request.setQueryString(query);
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    private static Result run(CanonicalHostFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    private record Result(MockHttpServletResponse response, boolean chained) {
    }

    @Test
    void aliasHostIsRedirectedToCanonicalHost() throws Exception {
        Result result = run(filter, request(ALIAS, "/", null));

        assertThat(result.response().getStatus()).isEqualTo(302);
        assertThat(result.response().getHeader("Location")).isEqualTo("https://" + CANONICAL + "/");
        assertThat(result.chained()).isFalse();
    }

    @Test
    void portOfAliasHostIsKept() throws Exception {
        Result result = run(filter, request(ALIAS + ":8082", "/index.html", null));

        assertThat(result.response().getHeader("Location")).isEqualTo("https://" + CANONICAL + ":8082/index.html");
        assertThat(result.chained()).isFalse();
    }

    @Test
    void aliasHostInUpperCaseIsRedirected() throws Exception {
        Result result = run(filter, request("CCAORGCHART", "/", null));

        assertThat(result.response().getStatus()).isEqualTo(302);
        assertThat(result.response().getHeader("Location")).isEqualTo("https://" + CANONICAL + "/");
    }

    @Test
    void pathAndQueryAreKept() throws Exception {
        Result result = run(filter, request(ALIAS, "/function.html", "cs=ITS&emp=a@b.kz"));

        assertThat(result.response().getHeader("Location"))
                .isEqualTo("https://" + CANONICAL + "/function.html?cs=ITS&emp=a@b.kz");
    }

    @Test
    void canonicalHostPassesThrough() throws Exception {
        Result result = run(filter, request(CANONICAL, "/function.html", "cs=ITS"));

        assertThat(result.chained()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.response().getHeader("Location")).isNull();
    }

    @Test
    void localhostPassesThrough() throws Exception {
        assertThat(run(filter, request("localhost:8082", "/actuator/health", null)).chained()).isTrue();
        assertThat(run(filter, request("127.0.0.1", "/", null)).chained()).isTrue();
        assertThat(run(filter, request("[::1]:8082", "/", null)).chained()).isTrue();
    }

    @Test
    void requestWithoutHostHeaderPassesThrough() throws Exception {
        assertThat(run(filter, request(null, "/", null)).chained()).isTrue();
    }

    @Test
    void emptyPropertiesDoNothing() throws Exception {
        CanonicalHostFilter off = new CanonicalHostFilter("", "", "");

        Result result = run(off, request(ALIAS, "/", null));

        assertThat(result.chained()).isTrue();
        assertThat(result.response().getHeader("Location")).isNull();
    }

    @Test
    void aliasesWithoutCanonicalHostDoNothing() throws Exception {
        assertThat(run(new CanonicalHostFilter("", ALIAS, ""), request(ALIAS, "/", null)).chained()).isTrue();
    }

    @Test
    void status301WhenConfigured() throws Exception {
        CanonicalHostFilter permanent = new CanonicalHostFilter(CANONICAL, ALIAS, "301");

        Result result = run(permanent, request(ALIAS, "/", null));

        assertThat(result.response().getStatus()).isEqualTo(301);
        assertThat(result.response().getHeader("Location")).isEqualTo("https://" + CANONICAL + "/");
    }

    @Test
    void severalAliasesAreAccepted() throws Exception {
        CanonicalHostFilter several = new CanonicalHostFilter(CANONICAL, " Other , ccaorgchart ", "");

        assertThat(run(several, request("other", "/", null)).chained()).isFalse();
        assertThat(run(several, request(ALIAS, "/", null)).chained()).isFalse();
    }

    @Test
    void canonicalHostListedAsAliasDoesNotLoop() throws Exception {
        CanonicalHostFilter listed = new CanonicalHostFilter(CANONICAL, ALIAS + "," + CANONICAL.toUpperCase(), "");

        assertThat(run(listed, request(CANONICAL, "/", null)).chained()).isTrue();
    }

    @Test
    void wrongSettingsAreRefused() {
        assertThatThrownBy(() -> new CanonicalHostFilter(CANONICAL, ALIAS, "307"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalHostFilter("https://" + CANONICAL, ALIAS, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
