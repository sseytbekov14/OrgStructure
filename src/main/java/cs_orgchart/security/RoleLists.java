package cs_orgchart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The roles of a signed-in person: everyone is USER; SYSTEM_ADMIN_EMAILS and HR_EDITOR_EMAILS (addresses separated
 * by commas, any case; empty: nobody) add SYSTEM_ADMIN and HR_EDITOR. Checked against the e-mail address and the
 * UPN of an SSO sign-in, or the account name of the password form.
 */
@Component
public class RoleLists {

    public static final String USER = "USER";
    public static final String HR_EDITOR = "HR_EDITOR";
    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final Set<String> systemAdmins;
    private final Set<String> hrEditors;

    public RoleLists(@Value("${auth.roles.system-admin-emails:}") String systemAdmins,
                     @Value("${auth.roles.hr-editor-emails:}") String hrEditors) {
        this.systemAdmins = list(systemAdmins);
        this.hrEditors = list(hrEditors);
    }

    /** The roles (without the ROLE_ prefix) of a person known by any of these names; nulls are skipped. */
    public List<String> rolesOf(String... names) {
        Set<String> keys = Arrays.stream(names).filter(Objects::nonNull)
                .map(name -> name.trim().toLowerCase(Locale.ROOT)).filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());
        List<String> roles = new ArrayList<>(List.of(USER));
        if (keys.stream().anyMatch(hrEditors::contains)) {
            roles.add(HR_EDITOR);
        }
        if (keys.stream().anyMatch(systemAdmins::contains)) {
            roles.add(SYSTEM_ADMIN);
        }
        return roles;
    }

    public List<GrantedAuthority> authoritiesOf(String... names) {
        return rolesOf(names).stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    static Set<String> list(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
