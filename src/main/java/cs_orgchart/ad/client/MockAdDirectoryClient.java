package cs_orgchart.ad.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.model.AdEmployee;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AD_SYNC_MODE=mock (dev): synthetic users from {@value #RESOURCE}, entries with the LDAP attribute names of spec
 * §4.3 (objectGUID as a UUID string). {@link #fetchAll()} applies the rules of the AD filter (§4.2): Central Services
 * departments and enabled accounts only; the lookups find any user, as in AD.
 */
@Slf4j
public class MockAdDirectoryClient implements AdDirectoryClient {

    public static final String RESOURCE = "mock/ad-employees.json";

    private final List<AdEmployee> users;

    public MockAdDirectoryClient(ObjectMapper json) {
        this(read(json));
    }

    MockAdDirectoryClient(List<AdEmployee> users) {
        this.users = List.copyOf(users);
    }

    @Override
    public List<AdEmployee> fetchAll() {
        List<AdEmployee> employees = users.stream()
                .filter(u -> u.enabled() && inCentralServices(u.department()))
                .toList();
        log.info("AD fetch (mock): {} entries", employees.size());
        return employees;
    }

    @Override
    public Optional<AdEmployee> findByDn(String dn) {
        return users.stream().filter(u -> u.dn().equalsIgnoreCase(String.valueOf(dn).trim())).findFirst();
    }

    @Override
    public Optional<AdEmployee> findByUpn(String upn) {
        return users.stream().filter(u -> u.upn().equalsIgnoreCase(String.valueOf(upn).trim())).findFirst();
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

    private static List<AdEmployee> read(ObjectMapper json) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            List<Map<String, Object>> entries = json.readValue(in, new TypeReference<>() {
            });
            List<AdEmployee> users = new ArrayList<>();
            for (Map<String, Object> entry : entries) {
                try {
                    users.add(AdEmployeeMapper.map(entry));
                } catch (IllegalArgumentException e) {
                    log.warn("{} ({})", e.getMessage(), RESOURCE);
                }
            }
            return users;
        } catch (IOException e) {
            throw new UncheckedIOException("AD_SYNC_MODE=mock but " + RESOURCE + " cannot be read", e);
        }
    }
}
