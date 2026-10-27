package cs_orgchart.ad.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.model.AdRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AD_SYNC_MODE=mock (dev): synthetic users from {@value #RESOURCE}, entries with the LDAP attribute names of spec
 * §4.3 (objectGUID as a UUID string). {@link #fetchAll()} applies the rules of the AD filter (§4.2): Central Services
 * departments, enabled accounts, no copilot-* accounts. Like AD, it returns entries the mapper then skips (§4.4);
 * the lookups find any user.
 */
@Slf4j
public class MockAdDirectoryClient implements AdDirectoryClient {

    public static final String RESOURCE = "mock/ad-employees.json";

    private final List<AdRecord> users;

    public MockAdDirectoryClient(ObjectMapper json) {
        this(read(json));
    }

    MockAdDirectoryClient(List<AdRecord> users) {
        this.users = List.copyOf(users);
    }

    @Override
    public List<AdRecord> fetchAll() {
        List<AdRecord> entries = users.stream().filter(MockAdDirectoryClient::matchesFilter).toList();
        log.info("AD fetch (mock): {} entries", entries.size());
        return entries;
    }

    @Override
    public Optional<AdRecord> findByDn(String dn) {
        return find("distinguishedName", dn);
    }

    @Override
    public Optional<AdRecord> findByUpn(String upn) {
        return find("userPrincipalName", upn);
    }

    private Optional<AdRecord> find(String attribute, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return users.stream()
                .filter(u -> value.trim().equalsIgnoreCase(Objects.toString(u.get(attribute), null)))
                .findFirst();
    }

    /** The filter of spec §4.2 */
    static boolean matchesFilter(AdRecord user) {
        String login = AdEmployeeMapper.text(user.get("sAMAccountName"));
        return inCentralServices(AdEmployeeMapper.text(user.get("department")))
                && !disabled(user.get("userAccountControl"))
                && (login == null || !login.toLowerCase(Locale.ROOT).startsWith("copilot-"));
    }

    /** As the AD filter: only an account with the ACCOUNTDISABLE bit set is left out */
    private static boolean disabled(Object userAccountControl) {
        try {
            return userAccountControl != null && !AdEmployeeMapper.enabled(userAccountControl);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The department part of the AD filter: {@code Central Services*}, {@code CS/*}, {@code CS /*} or {@code CS},
     * in any case
     */
    static boolean inCentralServices(String department) {
        if (department == null) {
            return false;
        }
        String d = department.toLowerCase(Locale.ROOT);
        return d.startsWith("central services") || d.startsWith("cs/") || d.startsWith("cs /") || d.equals("cs");
    }

    private static List<AdRecord> read(ObjectMapper json) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            List<Map<String, Object>> entries = json.readValue(in, new TypeReference<>() {
            });
            return entries.stream().map(AdRecord::new).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("AD_SYNC_MODE=mock but " + RESOURCE + " cannot be read", e);
        }
    }
}
