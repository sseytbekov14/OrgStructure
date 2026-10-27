package cs_orgchart.sso;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session cookie as the real server (Tomcat) writes it with SSO: SameSite=None, Secure, HttpOnly - without
 * None, Microsoft's POST to the Assertion Consumer Service would come without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.sso.enabled=true",
        "auth.sso.entity-id=" + TestIdp.ENTITY_ID,
        "auth.sso.acs-url=" + TestIdp.ACS_URL
})
@ActiveProfiles("test")
class SsoSessionCookieTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("auth.sso.metadata-uri", TestIdp::metadataUri);
    }

    @Test
    void theSignOn_setsTheSessionCookie_sameSiteNone_secure_httpOnly() throws Exception {
        HttpResponse<Void> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + TestIdp.SIGN_ON_PATH)).build(),
                        HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        String session = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(session).contains("SameSite=None").contains("Secure").contains("HttpOnly");
    }
}
