package cs_orgchart.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * How people get in: with Microsoft Entra ID over SAML ({@code auth.sso.enabled}, env AUTH_SSO_ENABLED), or -
 * with SSO off - without any sign-in, open to everyone who reaches the site (as before SSO). The password form
 * ({@code auth.password.enabled}, env AUTH_PASSWORD_ENABLED; accounts from AUTH_PASSWORD_USERS) is an optional
 * emergency sign-in instead of the open site; it never goes together with SSO. /logout follows
 * {@code auth.logout.enabled} (AUTH_LOGOUT_ENABLED), by default on only with the password form.
 * The prod profile defaults to SSO, stage and local to the open site; StartupGuard refuses impossible
 * combinations before anything starts.
 */
@Component
public class AuthMode {

    public static final String SSO = "auth.sso.enabled";
    public static final String PASSWORD = "auth.password.enabled";
    public static final String LOGOUT = "auth.logout.enabled";
    public static final String PASSWORD_USERS = "auth.password.users";

    private final boolean sso;
    private final boolean password;
    private final boolean logout;

    public AuthMode(Environment environment) {
        Optional<String> refusal = refusal(environment);
        if (refusal.isPresent()) {
            throw new IllegalStateException(refusal.get());
        }
        this.sso = flag(environment, SSO).orElse(false);
        this.password = flag(environment, PASSWORD).orElse(false);
        this.logout = flag(environment, LOGOUT).orElse(password);
    }

    /** Signed in with Microsoft Entra ID (SAML). */
    public boolean sso() {
        return sso;
    }

    /** The password form exists. */
    public boolean password() {
        return password;
    }

    /** No sign-in at all: everyone who reaches the site is let in. */
    public boolean open() {
        return !sso && !password;
    }

    /** /logout exists. */
    public boolean logout() {
        return logout && !open();
    }

    /** Why the sign-in settings cannot work, or empty. */
    public static Optional<String> refusal(Environment environment) {
        return refusal(environment, true);
    }

    /**
     * The same, optionally without the accounts of the password form (the tests make them after the
     * environment is prepared; this bean still checks them when it is made).
     */
    public static Optional<String> refusal(Environment environment, boolean checkAccounts) {
        for (String key : new String[]{SSO, PASSWORD, LOGOUT}) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank() && parse(value) == null) {
                return Optional.of(key + " must be true or false, not \"" + value.trim() + "\"");
            }
        }
        boolean sso = flag(environment, SSO).orElse(false);
        boolean password = flag(environment, PASSWORD).orElse(false);
        if (sso && password) {
            return Optional.of("AUTH_SSO_ENABLED and AUTH_PASSWORD_ENABLED are both true: the password form does"
                    + " not go together with SSO, set AUTH_PASSWORD_ENABLED=false");
        }
        if (password && checkAccounts) {
            String users = environment.getProperty(PASSWORD_USERS);
            if (users == null || users.isBlank()) {
                // local makes a one-off account (SecurityConfig); anywhere else nobody could sign in
                if (!environment.acceptsProfiles(Profiles.of("local"))) {
                    return Optional.of("the password form is on but AUTH_PASSWORD_USERS is empty: nobody could"
                            + " sign in (set user:bcrypt-hash pairs, or AUTH_PASSWORD_ENABLED=false)");
                }
            } else {
                try {
                    PasswordUsers.parse(users);
                } catch (IllegalArgumentException ex) {
                    return Optional.of("AUTH_PASSWORD_USERS: " + ex.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Boolean> flag(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.ofNullable(parse(value));
    }

    private static Boolean parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
