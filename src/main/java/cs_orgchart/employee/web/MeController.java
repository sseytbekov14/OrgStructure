package cs_orgchart.employee.web;

import cs_orgchart.employee.override.OverrideAdminService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GET /api/me/roles: the roles of the signed-in person, so that the pages show the edit controls only to
 * SYSTEM_ADMIN and only when editing is possible (EMPLOYEE_SOURCE=db). Hiding them is cosmetic: the server checks
 * every change. With sign-in on, SecurityConfig lets only signed-in people in; on the open site the answer has no
 * roles (never 401, which would make the pages reload).
 */
@RestController
public class MeController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final OverrideAdminService overrides;

    public MeController(OverrideAdminService overrides) {
        this.overrides = overrides;
    }

    @GetMapping("/api/me/roles")
    public Map<String, Object> roles(Authentication authentication) {
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        List<String> roles = !signedIn ? List.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()))
                .sorted()
                .toList();
        return Map.of("authenticated", signedIn, "roles", roles, "editingEnabled", overrides.editingEnabled());
    }
}
