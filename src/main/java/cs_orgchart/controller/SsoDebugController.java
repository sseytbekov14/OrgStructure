package cs_orgchart.controller;

import cs_orgchart.security.SsoAuthenticationToken;
import cs_orgchart.security.SsoClaims;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /me: what Microsoft sent about the signed-in person at their own sign-in (NameID and claims), to check the
 * claims of the Entra application. Only with auth.sso.debug-endpoint=true (AUTH_SSO_DEBUG_ENDPOINT, default
 * false); it reads nothing but the caller's own sign-in, there is no way to ask about someone else.
 * Switched on in prod it says so in the log at start.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "auth.sso.debug-endpoint", havingValue = "true")
public class SsoDebugController {

    private final Environment environment;

    public SsoDebugController(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void warnInProd() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("AUTH_SSO_DEBUG_ENDPOINT is on in prod: /me shows every signed-in person the claims Microsoft"
                    + " sent about them. Switch it off when the check is done.");
        }
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> answer = new LinkedHashMap<>();
        SsoClaims claims = authentication instanceof SsoAuthenticationToken sso ? sso.claims() : null;
        answer.put("name", authentication.getName());
        answer.put("roles", authentication.getAuthorities().stream().map(Object::toString).toList());
        if (claims != null) {
            answer.put("nameId", claims.nameId());
            answer.put("claims", claims.claims());
        } else {
            answer.put("message", "No SSO claims for this session (signed in with the password, or before"
                    + " the debug endpoint was switched on)");
        }
        return answer;
    }
}
