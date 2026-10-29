package cs_orgchart.employee.web;

import cs_orgchart.access.AdminAccess;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.override.OverrideAdminService;
import cs_orgchart.photo.PhotoUploadService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/me/roles: who the signed-in person is for the site, so that the pages load the admin tools only for admins
 * and show the edit controls only when editing is possible (EMPLOYEE_SOURCE=db). Hiding them is cosmetic: the server
 * checks every change. With sign-in on, SecurityConfig lets only signed-in people in; on the open site the answer
 * has no roles (never 401, which would make the pages reload).
 * <p>
 * {@code you} is the identifier the admin list (app_admins.login_name) is matched against (U1); {@code admin} and
 * {@code superAdmin} come from {@link AdminAccess}. Non-admins get admin=false and nothing else about admins.
 * {@code employeeId}: the person of the site the sign-in belongs to (their own card offers "Change photo"), null when
 * none; {@code photoUpload}: photos can be uploaded (EMPLOYEE_SOURCE=db).
 */
@RestController
public class MeController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final OverrideAdminService overrides;
    private final AdminAccess access;
    private final PhotoUploadService photoUploads;

    public MeController(OverrideAdminService overrides, AdminAccess access, PhotoUploadService photoUploads) {
        this.overrides = overrides;
        this.access = access;
        this.photoUploads = photoUploads;
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
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("authenticated", signedIn);
        me.put("you", signedIn ? authentication.getName() : null);
        me.put("roles", roles);
        me.put("admin", access.isAdmin(authentication));
        me.put("superAdmin", access.isSuperAdmin(authentication));
        me.put("editingEnabled", overrides.editingEnabled());
        me.put("employeeId", photoUploads.signedIn(authentication).map(Employee::getId).orElse(null));
        me.put("photoUpload", photoUploads.enabled());
        return me;
    }
}
