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

    private static final String USERS;

    static {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        USERS = ADMIN + ":" + encoder.encode(PASSWORD) + "," + READER + ":" + encoder.encode(PASSWORD);
    }

    private TestAccounts() {
    }

    /** AUTH_PASSWORD_USERS of the tests. */
    public static String users() {
        return USERS;
    }
}
