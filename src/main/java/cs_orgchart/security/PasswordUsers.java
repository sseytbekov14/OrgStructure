package cs_orgchart.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The accounts of the password form (emergency sign-in), from AUTH_PASSWORD_USERS:
 * {@code name:bcrypt-hash[,name:bcrypt-hash...]}. Only bcrypt hashes are accepted, never a plain password.
 * The messages name the account, never the hash.
 */
public final class PasswordUsers {

    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    public record Account(String username, String hash) {

        @Override
        public String toString() {
            return "Account[" + username + "]";
        }
    }

    private PasswordUsers() {
    }

    /** The accounts, or IllegalArgumentException saying which entry is wrong. */
    public static List<Account> parse(String value) {
        List<Account> accounts = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return accounts;
        }
        Set<String> names = new HashSet<>();
        String[] entries = value.split(",");
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            String name = colon < 0 ? "" : entry.substring(0, colon).trim();
            if (colon < 0 || name.isEmpty()) {
                throw new IllegalArgumentException("entry " + (i + 1) + " must be name:bcrypt-hash");
            }
            String hash = entry.substring(colon + 1).trim();
            if (!BCRYPT.matcher(hash).matches()) {
                throw new IllegalArgumentException("the password of \"" + name + "\" is not a bcrypt hash"
                        + " ($2a$/$2b$/$2y$...)");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("\"" + name + "\" is listed twice");
            }
            accounts.add(new Account(name, hash));
        }
        return accounts;
    }
}
