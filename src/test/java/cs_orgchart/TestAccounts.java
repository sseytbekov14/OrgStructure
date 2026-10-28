package cs_orgchart;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

/**
 * Accounts of the password form for the tests: a fresh password per run, hashed at run time (no hash or
 * password is stored in the repository). {@link #ADMIN} is on SYSTEM_ADMIN_EMAILS of {@link #properties()}.
 */
public final class TestAccounts {

    public static final String PASSWORD = "pw-" + UUID.randomUUID();
    public static final String ADMIN = "admin@corp.example";
    public static final String READER = "reader";

    /** Admin through the database list app_admins (U1), not through SYSTEM_ADMIN_EMAILS */
    public static final String EDITOR = "editor@corp.example";
    /** Another signed-in person for the admin list tests (disabled row, case-insensitive match) */
    public static final String OTHER = "other.person";

    private static final String USERS;
    private static final String USERS_WITH_EDITORS;

    static {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        USERS = ADMIN + ":" + encoder.encode(PASSWORD) + "," + READER + ":" + encoder.encode(PASSWORD);
        USERS_WITH_EDITORS = USERS + "," + EDITOR + ":" + encoder.encode(PASSWORD)
                + "," + OTHER + ":" + encoder.encode(PASSWORD);
    }

    private TestAccounts() {
    }

    /** AUTH_PASSWORD_USERS of the tests. */
    public static String users() {
        return USERS;
    }

    /** AUTH_PASSWORD_USERS with {@link #EDITOR} and {@link #OTHER} as well. */
    public static String usersWithEditors() {
        return USERS_WITH_EDITORS;
    }
}
