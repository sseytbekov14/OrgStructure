package cs_orgchart.ad.client;

import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.model.AdEmployee;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapOperations;
import org.springframework.ldap.core.support.SingleContextSource;
import org.springframework.ldap.support.LdapEncoder;

import javax.naming.InvalidNameException;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AD_SYNC_MODE=ldap (spec v2.1 §4): a paged subtree search under AD_LDAP_BASE_DN with AD_LDAP_FILTER, all pages on
 * ONE connection (paging cookies belong to the connection that made them), only the attributes of §4.3. Single users
 * are looked up under the base DN first, then under the domain root. Every failure is an {@link AdDirectoryException};
 * nothing is retried here (a wrong password must not lock the account).
 */
@Slf4j
public class LdapAdDirectoryClient implements AdDirectoryClient {

    private static final String USERS = "(objectCategory=person)(objectClass=user)";

    private final ContextSource contextSource;
    private final AdDirectoryException unusable;
    private final String baseDn;
    private final String domainRoot;
    private final String filter;
    private final int pageSize;
    private final String target;

    public LdapAdDirectoryClient(ContextSource contextSource, AdLdapProperties.Ldap settings) {
        this(contextSource, null, settings);
    }

    private LdapAdDirectoryClient(ContextSource contextSource, AdDirectoryException unusable,
                                  AdLdapProperties.Ldap settings) {
        this.contextSource = contextSource;
        this.unusable = unusable;
        this.baseDn = settings.getBaseDn() == null ? "" : settings.getBaseDn().trim();
        this.domainRoot = domainRoot(baseDn);
        this.filter = settings.effectiveFilter();
        this.pageSize = settings.getPageSize();
        this.target = target(settings.getUrl());
    }

    /** A client whose every read fails with {@code reason}: the settings cannot be used */
    public static LdapAdDirectoryClient unusable(AdDirectoryException reason, AdLdapProperties.Ldap settings) {
        return new LdapAdDirectoryClient(null, reason, settings);
    }

    @Override
    public List<AdEmployee> fetchAll() {
        ensureUsable();
        long started = System.nanoTime();
        try {
            return SingleContextSource.doWithSingleContext(contextSource, operations -> {
                List<AdEmployee> employees = new ArrayList<>();
                int pages = 0;
                int skipped = 0;
                PagedResultsDirContextProcessor pager = new PagedResultsDirContextProcessor(pageSize);
                boolean more;
                do {
                    List<Map<String, Object>> entries =
                            operations.search(baseDn, filter, controls(), ENTRY, pager);
                    pages++;
                    for (Map<String, Object> entry : entries) {
                        Optional<AdEmployee> employee = map(entry);
                        if (employee.isPresent()) {
                            employees.add(employee.get());
                        } else {
                            skipped++;
                        }
                    }
                    more = pager.hasMore();
                    pager = new PagedResultsDirContextProcessor(pageSize, pager.getCookie());
                } while (more);
                log.info("AD fetch: {} entries in {} page(s) of up to {}, {} skipped, {} ms", employees.size(), pages,
                        pageSize, skipped, (System.nanoTime() - started) / 1_000_000);
                return employees;
            }, true, true, false);
        } catch (RuntimeException e) {
            throw AdErrorClassifier.classify(e, target);
        }
    }

    @Override
    public Optional<AdEmployee> findByDn(String dn) {
        return findOne("distinguishedName", dn);
    }

    @Override
    public Optional<AdEmployee> findByUpn(String upn) {
        return findOne("userPrincipalName", upn);
    }

    /** Under the base DN, then under the domain root; one connection for both */
    private Optional<AdEmployee> findOne(String attribute, String value) {
        ensureUsable();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String query = "(&" + USERS + "(" + attribute + "=" + LdapEncoder.filterEncode(value.trim()) + "))";
        try {
            return SingleContextSource.doWithSingleContext(contextSource, operations -> {
                Optional<AdEmployee> found = searchOne(operations, baseDn, query);
                if (found.isEmpty() && !domainRoot.isEmpty() && !domainRoot.equalsIgnoreCase(baseDn)) {
                    found = searchOne(operations, domainRoot, query);
                }
                return found;
            }, true, true, true);
        } catch (RuntimeException e) {
            throw AdErrorClassifier.classify(e, target);
        }
    }

    private Optional<AdEmployee> searchOne(LdapOperations operations, String base, String query) {
        return operations.search(base, query, controls(), ENTRY).stream()
                .map(this::map)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<AdEmployee> map(Map<String, Object> entry) {
        try {
            return Optional.of(AdEmployeeMapper.map(entry));
        } catch (IllegalArgumentException e) {
            log.warn("{}", e.getMessage());
            return Optional.empty();
        }
    }

    private void ensureUsable() {
        if (unusable != null) {
            throw unusable;
        }
    }

    private static SearchControls controls() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(AdEmployeeMapper.AD_ATTRIBUTES);
        controls.setReturningObjFlag(true);
        return controls;
    }

    /** One entry as attribute name → first value; the DN of the entry when AD did not return distinguishedName */
    private static final ContextMapper<Map<String, Object>> ENTRY = context -> {
        DirContextAdapter adapter = (DirContextAdapter) context;
        Map<String, Object> entry = new HashMap<>();
        for (String name : AdEmployeeMapper.AD_ATTRIBUTES) {
            Object value = adapter.getObjectAttribute(name);
            if (value != null) {
                entry.put(name, value);
            }
        }
        entry.putIfAbsent("distinguishedName", adapter.getNameInNamespace());
        return entry;
    };

    /** DC=go1,DC=kworld,DC=kpmg,DC=com of OU=Countries,OU=CCG,DC=go1,DC=kworld,DC=kpmg,DC=com */
    static String domainRoot(String baseDn) {
        try {
            List<Rdn> rdns = new LdapName(baseDn).getRdns();
            List<String> dcs = new ArrayList<>();
            for (int i = rdns.size() - 1; i >= 0; i--) {
                if ("DC".equalsIgnoreCase(rdns.get(i).getType())) {
                    dcs.add(rdns.get(i).toString());
                }
            }
            return String.join(",", dcs);
        } catch (InvalidNameException | IllegalArgumentException e) {
            return "";
        }
    }

    private static String target(String url) {
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() == null ? url.trim() : uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort()
                    : "ldaps".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 636 : 389);
        } catch (RuntimeException e) {
            return String.valueOf(url);
        }
    }
}
