package cs_orgchart.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SessionCookieDefaultsTest {

    @Test
    void withSso_sameSiteNone_secure_httpOnly() {
        MockEnvironment env = new MockEnvironment().withProperty("auth.sso.enabled", "true");
        assertThat(SessionCookieDefaults.defaults(env)).containsOnly(
                entry(SessionCookieDefaults.SAME_SITE, "none"),
                entry(SessionCookieDefaults.SECURE, "true"),
                entry(SessionCookieDefaults.HTTP_ONLY, "true"));
    }

    @Test
    void withoutSso_theSettingsOfBefore() {
        assertThat(SessionCookieDefaults.defaults(new MockEnvironment())).containsOnly(
                entry(SessionCookieDefaults.SAME_SITE, "strict"),
                entry(SessionCookieDefaults.SECURE, "false"),
                entry(SessionCookieDefaults.HTTP_ONLY, "true"));
    }

    @Test
    void aGivenValue_wins_anEmptyOneDoesNot() {
        MockEnvironment env = new MockEnvironment().withProperty("auth.sso.enabled", "true")
                .withProperty(SessionCookieDefaults.SAME_SITE, "lax")
                .withProperty(SessionCookieDefaults.SECURE, " ");
        assertThat(SessionCookieDefaults.defaults(env)).containsOnly(
                entry(SessionCookieDefaults.SECURE, "true"),
                entry(SessionCookieDefaults.HTTP_ONLY, "true"));
    }
}
