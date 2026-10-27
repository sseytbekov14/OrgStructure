package cs_orgchart.ad.mapper;

import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.ad.model.AdRecord;
import lombok.extern.slf4j.Slf4j;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates an AD entry and maps it to an {@link AdEmployee} (spec v3 §4.3, §4.4): objectGUID → UUID,
 * userAccountControl → enabled, DN → country code (whitelist AD_LDAP_COUNTRIES), whenChanged → Instant, values
 * trimmed and empty ones null. A record that cannot be stored is {@link Skipped skipped} with its
 * {@link SkipReason}, never an exception: one bad account must not fail the sync. {@link Run} counts the skips of a
 * sync run and logs them without names or e-mails.
 */
@Slf4j
public class AdEmployeeMapper {

    public static final String[] AD_ATTRIBUTES = {
            "objectGUID", "distinguishedName", "sAMAccountName", "userPrincipalName",
            "mail", "displayName", "givenName", "sn", "title", "department", "company",
            "manager", "kPMG-User-PerformanceManagerUPN", "physicalDeliveryOfficeName",
            "l", "telephoneNumber", "mobile", "employeeID", "userAccountControl", "whenChanged"
    };

    /** Skipped records logged one by one per run; the rest only in the summary line */
    static final int MAX_LOGGED_SKIPS = 20;

    /** Why a record was not stored (spec §4.4) */
    public enum SkipReason {
        /** No OU=&lt;XX&gt; of AD_LDAP_COUNTRIES directly before OU=Countries in the DN (e.g. Test for Dev Team) */
        COUNTRY_NOT_ALLOWED,
        /** displayName null, empty or only spaces */
        BLANK_DISPLAY_NAME,
        /** objectGUID, distinguishedName, sAMAccountName, userPrincipalName or userAccountControl missing or unusable */
        MISSING_REQUIRED
    }

    /** The outcome for one record */
    public sealed interface Result permits Mapped, Skipped {
    }

    public record Mapped(AdEmployee employee) implements Result {
    }

    /**
     * @param guid the objectGUID, or "n/a"
     * @param ou   the DN without its CN part, or "n/a": no names or e-mails
     */
    public record Skipped(SkipReason reason, String guid, String ou) implements Result {
    }

    /** The records of a fetch that can be stored, and how many were skipped for each reason */
    public record Batch(List<AdEmployee> employees, Map<SkipReason, Integer> skipped) {

        public int skippedCount() {
            return skipped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final String NOT_AVAILABLE = "n/a";
    private static final int ACCOUNT_DISABLE = 0x2;
    private static final int COUNTRY_CODE_MAX = 8;
    private static final Pattern GENERALIZED_TIME =
            Pattern.compile("(\\d{14})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{4})?");
    private static final DateTimeFormatter GENERALIZED_TIME_SECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Set<String> allowedCountries;

    /**
     * @param allowedCountries country OUs whose employees are synced (AD_LDAP_COUNTRIES), any case
     */
    public AdEmployeeMapper(Collection<String> allowedCountries) {
        Set<String> upper = new LinkedHashSet<>();
        allowedCountries.forEach(code -> upper.add(code.trim().toUpperCase(Locale.ROOT)));
        this.allowedCountries = Collections.unmodifiableSet(upper);
    }

    public Set<String> allowedCountries() {
        return allowedCountries;
    }

    /** A Central Services employee from the sync filter: all rules of §4.4 */
    public Result map(AdRecord record) {
        return validate(record, true);
    }

    /**
     * A manager or PM looked up outside the filter (findByDn / findByUpn): exempt from the country rule, its
     * {@code countryCode} stays null; displayName and the required values are still checked.
     */
    public Result mapExternal(AdRecord record) {
        return validate(record, false);
    }

    /** The records of one fetch, in one {@link Run} */
    public Batch mapAll(Collection<AdRecord> records) {
        Run run = newRun();
        List<AdEmployee> employees = new ArrayList<>();
        for (AdRecord record : records) {
            if (run.map(record) instanceof Mapped mapped) {
                employees.add(mapped.employee());
            }
        }
        run.finish();
        return new Batch(List.copyOf(employees), run.skipped());
    }

    /** A sync run: counts the skipped records and logs at most {@value #MAX_LOGGED_SKIPS} of them */
    public Run newRun() {
        return new Run();
    }

    public final class Run {

        private final Map<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
        private int logged;
        private boolean finished;

        private Run() {
            for (SkipReason reason : SkipReason.values()) {
                skipped.put(reason, 0);
            }
        }

        public Result map(AdRecord record) {
            return count(AdEmployeeMapper.this.map(record));
        }

        public Result mapExternal(AdRecord record) {
            return count(AdEmployeeMapper.this.mapExternal(record));
        }

        /** Per reason, every reason present (0 when none) */
        public Map<SkipReason, Integer> skipped() {
            return Collections.unmodifiableMap(new EnumMap<>(skipped));
        }

        public int skippedCount() {
            return skipped.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** The summary line of the run (once, when anything was skipped) */
        public void finish() {
            int total = skippedCount();
            if (finished || total == 0) {
                finished = true;
                return;
            }
            finished = true;
            String perReason = skipped.entrySet().stream().filter(e -> e.getValue() > 0)
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
            log.warn("Skipped AD records in this run: {} ({}){}", total, perReason,
                    total > logged ? "; only the first " + logged + " are listed" : "");
        }

        private Result count(Result result) {
            if (result instanceof Skipped skip) {
                skipped.merge(skip.reason(), 1, Integer::sum);
                if (logged < MAX_LOGGED_SKIPS) {
                    logged++;
                    log.warn("Skipped AD record: reason={}, guid={}, ou={}", skip.reason(), skip.guid(), skip.ou());
                }
            }
            return result;
        }
    }

    private Result validate(AdRecord record, boolean centralServices) {
        String dn = text(record.get("distinguishedName"));
        UUID guid = guidOrNull(record.get("objectGUID"));
        String guidText = guid == null ? NOT_AVAILABLE : guid.toString();
        String ou = ou(dn);
        if (dn == null) {
            return new Skipped(SkipReason.MISSING_REQUIRED, guidText, ou);
        }
        String countryCode = null;
        if (centralServices) {
            countryCode = allowedCountry(dn);
            if (countryCode == null) {
                return new Skipped(SkipReason.COUNTRY_NOT_ALLOWED, guidText, ou);
            }
        }
        String displayName = text(record.get("displayName"));
        if (displayName == null) {
            return new Skipped(SkipReason.BLANK_DISPLAY_NAME, guidText, ou);
        }
        String login = text(record.get("sAMAccountName"));
        String upn = text(record.get("userPrincipalName"));
        Boolean enabled = enabledOrNull(record.get("userAccountControl"));
        if (guid == null || login == null || upn == null || enabled == null) {
            return new Skipped(SkipReason.MISSING_REQUIRED, guidText, ou);
        }
        return new Mapped(new AdEmployee(
                guid,
                dn,
                login,
                upn,
                text(record.get("mail")),
                displayName,
                text(record.get("givenName")),
                text(record.get("sn")),
                text(record.get("title")),
                text(record.get("department")),
                text(record.get("company")),
                text(record.get("manager")),
                text(record.get("kPMG-User-PerformanceManagerUPN")),
                text(record.get("physicalDeliveryOfficeName")),
                text(record.get("l")),
                text(record.get("telephoneNumber")),
                text(record.get("mobile")),
                text(record.get("employeeID")),
                enabled,
                generalizedTime(record.get("whenChanged")),
                countryCode));
    }

    /**
     * The XX of the RDN OU=XX directly before OU=Countries, upper case, if it is an allowed country; null otherwise
     * (other OU, no OU=Countries, DN not readable)
     */
    String allowedCountry(String dn) {
        List<Rdn> rdns = rdns(dn);
        // LdapName lists the RDNs right to left: the one written before OU=Countries comes after it
        for (int i = 0; i < rdns.size() - 1; i++) {
            if (isOu(rdns.get(i), "Countries")) {
                Rdn country = rdns.get(i + 1);
                if (!"OU".equalsIgnoreCase(country.getType())) {
                    return null;
                }
                String code = String.valueOf(country.getValue()).trim().toUpperCase(Locale.ROOT);
                return code.length() <= COUNTRY_CODE_MAX && allowedCountries.contains(code) ? code : null;
            }
        }
        return null;
    }

    /** The DN without its CN part (OU=Almaty,OU=Users,OU=KZ,...), for the log: never the name of the account */
    static String ou(String dn) {
        if (dn == null) {
            return NOT_AVAILABLE;
        }
        List<Rdn> rdns = rdns(dn);
        if (rdns.isEmpty()) {
            return NOT_AVAILABLE;
        }
        Rdn first = rdns.get(rdns.size() - 1);
        List<Rdn> rest = "CN".equalsIgnoreCase(first.getType()) ? rdns.subList(0, rdns.size() - 1) : rdns;
        return rest.isEmpty() ? NOT_AVAILABLE : new LdapName(rest).toString();
    }

    private static List<Rdn> rdns(String dn) {
        try {
            return new LdapName(dn).getRdns();
        } catch (InvalidNameException | IllegalArgumentException e) {
            return List.of();
        }
    }

    private static boolean isOu(Rdn rdn, String value) {
        return "OU".equalsIgnoreCase(rdn.getType()) && value.equalsIgnoreCase(String.valueOf(rdn.getValue()).trim());
    }

    /**
     * objectGUID as 16 bytes (the first three blocks little-endian, as AD stores them) or as a UUID string.
     *
     * @throws IllegalArgumentException when it is neither
     */
    public static UUID guid(Object value) {
        if (value instanceof byte[] bytes) {
            if (bytes.length != 16) {
                throw new IllegalArgumentException("objectGUID must be 16 bytes, got " + bytes.length);
            }
            ByteBuffer little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            long high = ((long) little.getInt(0) & 0xFFFFFFFFL) << 32
                    | ((long) little.getShort(4) & 0xFFFFL) << 16
                    | ((long) little.getShort(6) & 0xFFFFL);
            long low = ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
            return new UUID(high, low);
        }
        if (value instanceof String text) {
            return UUID.fromString(text.trim());
        }
        throw new IllegalArgumentException("objectGUID has an unexpected type: "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /** The 16 bytes AD stores for this GUID (inverse of {@link #guid(Object)}) */
    public static byte[] guidBytes(UUID guid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) (guid.getMostSignificantBits() >>> 32))
                .putShort((short) (guid.getMostSignificantBits() >>> 16))
                .putShort((short) guid.getMostSignificantBits());
        buffer.order(ByteOrder.BIG_ENDIAN).putLong(guid.getLeastSignificantBits());
        return buffer.array();
    }

    /** enabled = (userAccountControl &amp; 2) == 0 */
    public static boolean enabled(Object userAccountControl) {
        long uac = userAccountControl instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(userAccountControl).trim());
        return (uac & ACCOUNT_DISABLE) == 0;
    }

    /** GeneralizedTime such as 20261023081530.0Z; null when empty or not readable */
    public static Instant generalizedTime(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        Matcher m = GENERALIZED_TIME.matcher(text);
        if (!m.matches()) {
            return null;
        }
        LocalDateTime local = LocalDateTime.parse(m.group(1), GENERALIZED_TIME_SECONDS);
        if (m.group(2) != null) {
            local = local.withNano(Integer.parseInt((m.group(2) + "000000000").substring(0, 9)));
        }
        ZoneOffset offset = m.group(3) == null || "Z".equals(m.group(3))
                ? ZoneOffset.UTC
                : ZoneOffset.of(m.group(3).substring(0, 3) + ":" + m.group(3).substring(3));
        return local.toInstant(offset);
    }

    /** Trimmed text, null when empty */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = (value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(value)).trim();
        return text.isEmpty() ? null : text;
    }

    private static UUID guidOrNull(Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        try {
            return guid(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Boolean enabledOrNull(Object userAccountControl) {
        if (text(userAccountControl) == null && !(userAccountControl instanceof Number)) {
            return null;
        }
        try {
            return enabled(userAccountControl);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
