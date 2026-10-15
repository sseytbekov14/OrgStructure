package cs_orgchart.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session cookie. With SSO: SameSite=None, Secure, HttpOnly - Microsoft posts the answer to the Assertion
 * Consumer Service from its own site, and with SameSite=Lax or Strict that POST comes without the session cookie,
 * so OrgChart would not find the request it sent nor the page to come back to. None needs Secure (HTTPS only).
 * Without SSO: SameSite=Strict, HttpOnly, Secure off (as before SSO).
 * Only defaults: a value given in SERVER_SERVLET_SESSION_COOKIE_SAME_SITE, _SECURE or _HTTP_ONLY (or the same
 * properties anywhere else) wins; an empty one counts as not given. Registered in META-INF/spring.factories.
 */
public class SessionCookieDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "orgchartSessionCookieDefaults";
    static final String SAME_SITE = "server.servlet.session.cookie.same-site";
    static final String SECURE = "server.servlet.session.cookie.secure";
    static final String HTTP_ONLY = "server.servlet.session.cookie.http-only";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = defaults(environment);
        if (!defaults.isEmpty()) {
            // Only keys nobody gave a value: first, so an empty variable does not hide the default
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, defaults));
        }
    }

    /** The cookie settings of the sign-in mode that nobody has set. */
    static Map<String, Object> defaults(Environment environment) {
        boolean sso = "true".equalsIgnoreCase(String.valueOf(environment.getProperty("auth.sso.enabled")).trim());
        Map<String, Object> defaults = new LinkedHashMap<>();
        putIfUnset(environment, defaults, SAME_SITE, sso ? "none" : "strict");
        putIfUnset(environment, defaults, SECURE, sso ? "true" : "false");
        putIfUnset(environment, defaults, HTTP_ONLY, "true");
        return defaults;
    }

    private static void putIfUnset(Environment environment, Map<String, Object> defaults, String key, String value) {
        String current = environment.getProperty(key);
        if (current == null || current.isBlank()) {
            defaults.put(key, value);
        }
    }

    @Override
    public int getOrder() {
        // After the configuration files are read (ConfigDataEnvironmentPostProcessor), before StartupGuard
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
