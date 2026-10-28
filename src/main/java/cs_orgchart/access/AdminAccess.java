package cs_orgchart.access;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Who may administer the site (U1), used as {@code @PreAuthorize("@adminAccess.isAdmin(authentication)")} on
 * /api/manage/**:
 * <ul>
 *   <li>super admins: ROLE_SYSTEM_ADMIN from the env list SYSTEM_ADMIN_EMAILS (bootstrap, break-glass, manage the
 *       admin list, run the AD sync);</li>
 *   <li>admins: the super admins plus the enabled rows of app_admins whose login_name equals, ignoring case, the
 *       name of the signed-in person ({@link Authentication#getName()}: the identifier already used for updated_by
 *       and shown as "you" by GET /api/me/roles).</li>
 * </ul>
 * The enabled names are read at most every 30 s and re-read right after every change made through the API.
 * Anonymous callers (also on the open site) are never admins.
 */
@Slf4j
@Component("adminAccess")
public class AdminAccess {

    static final String SUPER_ADMIN_ROLE = "ROLE_SYSTEM_ADMIN";
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final AppAdminRepository admins;

    private volatile Set<String> enabledNames;
    private volatile long loadedAt;

    public AdminAccess(AppAdminRepository admins) {
        this.admins = admins;
    }

    public boolean isSuperAdmin(Authentication authentication) {
        return signedIn(authentication) && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SUPER_ADMIN_ROLE::equals);
    }

    public boolean isAdmin(Authentication authentication) {
        if (!signedIn(authentication)) {
            return false;
        }
        if (isSuperAdmin(authentication)) {
            return true;
        }
        String name = authentication.getName();
        return name != null && !name.isBlank() && enabledNames().contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Forget the cached names: the next check reads the table again */
    public void evict() {
        enabledNames = null;
    }

    private Set<String> enabledNames() {
        Set<String> names = enabledNames;
        if (names == null || System.nanoTime() - loadedAt > CACHE_TTL.toNanos()) {
            try {
                names = Set.copyOf(admins.findEnabledLoginNames());
            } catch (RuntimeException e) {
                // The database is down: nobody from the table is an admin until it is back (env super admins still are)
                log.warn("Admin list unavailable: {}", e.getClass().getSimpleName());
                return Set.of();
            }
            loadedAt = System.nanoTime();
            enabledNames = names;
        }
        return names;
    }

    private static boolean signedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
