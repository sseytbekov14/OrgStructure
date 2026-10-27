package cs_orgchart.ad.mapper;

import cs_orgchart.ad.model.AdEmployee;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LDAP attributes (spec v2.1 §4.3) → {@link AdEmployee}: objectGUID → UUID, userAccountControl → enabled,
 * DN → country code, whenChanged (GeneralizedTime) → Instant, values trimmed and empty ones null.
 * The input is one entry as attribute name → first value (String, or byte[] for objectGUID); the mock fixture
 * uses the same names with objectGUID as a UUID string.
 */
public final class AdEmployeeMapper {

    public static final String[] AD_ATTRIBUTES = {
            "objectGUID", "distinguishedName", "sAMAccountName", "userPrincipalName",
            "mail", "displayName", "givenName", "sn", "title", "department", "company",
            "manager", "kPMG-User-PerformanceManagerUPN", "physicalDeliveryOfficeName",
            "l", "telephoneNumber", "mobile", "employeeID", "userAccountControl", "whenChanged"
    };

    /** Without these an entry cannot be stored (employees NOT NULL columns, enabled) */
    static final List<String> REQUIRED = List.of(
            "objectGUID", "distinguishedName", "sAMAccountName", "userPrincipalName", "displayName",
            "userAccountControl");

    /** ACCOUNTDISABLE bit of userAccountControl */
    private static final int ACCOUNT_DISABLE = 0x2;

    private static final Pattern COUNTRY_OU = Pattern.compile("(?i)(?:^|,)\\s*OU=([^,]+?)\\s*,\\s*OU=Countries(?:,|$)");
    private static final int COUNTRY_CODE_MAX = 8;

    private static final Pattern GENERALIZED_TIME =
            Pattern.compile("(\\d{14})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{4})?");
    private static final DateTimeFormatter GENERALIZED_TIME_SECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private AdEmployeeMapper() {
    }

    /**
     * @throws IllegalArgumentException when a required attribute is missing or unusable (the entry is skipped)
     */
    public static AdEmployee map(Map<String, ?> attributes) {
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED) {
            Object value = get(attributes, name);
            if (value == null || (value instanceof String s && s.isBlank())) {
                missing.add(name);
            }
        }
        String dn = text(get(attributes, "distinguishedName"));
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("AD entry " + (dn == null ? "(no DN)" : dn)
                    + " skipped: missing required attribute(s) " + String.join(", ", missing));
        }
        return new AdEmployee(
                guid(get(attributes, "objectGUID")),
                dn,
                text(get(attributes, "sAMAccountName")),
                text(get(attributes, "userPrincipalName")),
                text(get(attributes, "mail")),
                text(get(attributes, "displayName")),
                text(get(attributes, "givenName")),
                text(get(attributes, "sn")),
                text(get(attributes, "title")),
                text(get(attributes, "department")),
                text(get(attributes, "company")),
                text(get(attributes, "manager")),
                text(get(attributes, "kPMG-User-PerformanceManagerUPN")),
                text(get(attributes, "physicalDeliveryOfficeName")),
                text(get(attributes, "l")),
                text(get(attributes, "telephoneNumber")),
                text(get(attributes, "mobile")),
                text(get(attributes, "employeeID")),
                enabled(get(attributes, "userAccountControl")),
                generalizedTime(get(attributes, "whenChanged")),
                countryCode(dn));
    }

    /**
     * objectGUID as 16 bytes (the first three blocks little-endian, as AD stores them) or as a UUID string.
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

    /** The XX of OU=XX,OU=Countries in the DN, upper case; null when the entry is not under OU=Countries */
    public static String countryCode(String dn) {
        if (dn == null) {
            return null;
        }
        Matcher m = COUNTRY_OU.matcher(dn);
        if (!m.find()) {
            return null;
        }
        String code = m.group(1).trim().toUpperCase(Locale.ROOT);
        return code.isEmpty() || code.length() > COUNTRY_CODE_MAX ? null : code;
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

    /** LDAP attribute names are case-insensitive */
    private static Object get(Map<String, ?> attributes, String name) {
        Object value = attributes.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
