package cs_orgchart.ad.client;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.config.LdapConfig;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.mapper.AdEmployeeMapper.SkipReason;
import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.ad.model.AdRecord;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.ad.sync.SyncTrigger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link LdapAdDirectoryClient} against an in-memory LDAP server shaped like the AD of spec §2.1 (schema checks off):
 * the real filter of §4.2, paging on one connection, binary objectGUID, missing attributes, lookups outside the base
 * DN, and the failures that must not be retried.
 */
class LdapAdDirectoryClientTest {

    static final String ROOT = "DC=corp,DC=example";
    static final String BASE = "OU=Countries,OU=CCG," + ROOT;
    static final String BIND_DN = "CN=svc-orgchart,OU=Service," + ROOT;
    static final String PASSWORD = "test-only-secret";

    private static InMemoryDirectoryServer server;
    private static final Counter counter = new Counter();

    @BeforeAll
    static void startServer() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(ROOT);
        config.setSchema(null);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
        config.addAdditionalBindCredentials(BIND_DN, PASSWORD);
        config.addInMemoryOperationInterceptor(counter);
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        addDirectory(server);
    }

    @AfterAll
    static void stopServer() {
        server.shutDown(true);
    }

    @BeforeEach
    void resetCounter() {
        counter.reset();
    }

    /**
     * The tree of the tests: 14 entries match the filter, 11 of them can be stored (one without objectGUID, one under
     * OU=Test for Dev Team, one without displayName); users the filter must leave out.
     */
    static void addDirectory(InMemoryDirectoryServer directory) throws Exception {
        directory.add("dn: " + ROOT, "objectClass: domain", "dc: corp");
        for (String ou : List.of("OU=CCG," + ROOT, BASE, "OU=KZ," + BASE, "OU=UZ," + BASE,
                "OU=Test for Dev Team," + BASE, "OU=Service," + ROOT, "OU=Partners," + ROOT)) {
            directory.add("dn: " + ou, "objectClass: organizationalUnit");
        }
        String[] departments = {
                "CS/ITS/ITS/ITS General", "CS / ITS", "Central services/ITS/ITS General/IT Operations",
                "CENTRAL SERVICES/Finance/Finance/Finance General", "CS/People/People/HR Admin", "CS / QRM",
                "Central services/OGC/OGC/OGC General", "CS/F&O/Facilities & Operations/Proposals & Tenders",
                "CS / Administration Office", "CS"};
        for (int i = 0; i < departments.length; i++) {
            directory.add(user(String.format("user%02d", i), i % 2 == 0 ? "KZ" : "UZ", departments[i], 512));
        }
        // Only the required attributes, no distinguishedName attribute (the DN of the entry is used)
        Entry minimal = new Entry("CN=minimal,OU=KZ," + BASE);
        minimal.addAttribute("objectClass", "user");
        minimal.addAttribute("objectCategory", "person");
        minimal.addAttribute("objectGUID", AdEmployeeMapper.guidBytes(guid(50)));
        minimal.addAttribute("sAMAccountName", "minimal");
        minimal.addAttribute("userPrincipalName", "minimal@kz.example");
        minimal.addAttribute("displayName", "Minimal, Only");
        minimal.addAttribute("department", "CS/QRM/QRM/QRM General");
        minimal.addAttribute("userAccountControl", "512");
        directory.add(minimal);
        // Match the filter but cannot be stored: skipped by the mapper (spec §4.4)
        Entry noGuid = user("noguid", "KZ", "CS/ITS/ITS/ITS General", 512);
        noGuid.removeAttribute("objectGUID");
        directory.add(noGuid);
        directory.add(user("tdev", "Test for Dev Team", "CS/ITS/ITS/ITS General", 512));
        Entry noName = user("noname", "KZ", "CS/Finance/Finance/Finance General", 512);
        noName.removeAttribute("displayName");
        directory.add(noName);
        // Left out by the filter
        directory.add(user("copilot-agent01", "KZ", "CS/ITS/ITS/ITS General", 512));
        directory.add(user("disabled", "KZ", "CS/ITS/ITS/ITS General", 514));
        directory.add(user("auditor", "KZ", "Audit/Financial Services", 512));
        directory.add(user("csvlike", "KZ", "CSV Team/Data", 512));
        // Outside the base DN: found only through the domain root
        Entry partner = user("partner", "KZ", "Advisory/Management", 512);
        partner.setDN("CN=partner,OU=Partners," + ROOT);
        partner.setAttribute("distinguishedName", partner.getDN());
        directory.add(partner);
    }

    static Entry user(String login, String country, String department, int uac) {
        Entry entry = new Entry("CN=" + login + ",OU=" + country + "," + BASE);
        entry.addAttribute("objectClass", "top", "person", "organizationalPerson", "user");
        entry.addAttribute("objectCategory", "person");
        entry.addAttribute("objectGUID", AdEmployeeMapper.guidBytes(guid(login.hashCode() & 0xFFFF)));
        entry.addAttribute("distinguishedName", entry.getDN());
        entry.addAttribute("sAMAccountName", login);
        entry.addAttribute("userPrincipalName", login + "@kz.example");
        entry.addAttribute("mail", login + ".mail@kz.example");
        entry.addAttribute("displayName", "User, " + login);
        entry.addAttribute("givenName", login);
        entry.addAttribute("sn", "User");
        entry.addAttribute("title", "Specialist");
        entry.addAttribute("department", department);
        entry.addAttribute("manager", "CN=partner,OU=Partners," + ROOT);
        entry.addAttribute("kPMG-User-PerformanceManagerUPN", "partner@kz.example");
        entry.addAttribute("l", "Almaty");
        entry.addAttribute("userAccountControl", String.valueOf(uac));
        entry.addAttribute("whenChanged", "20261023081530.0Z");
        return entry;
    }

    static UUID guid(int n) {
        return UUID.fromString(String.format("b0000000-0000-4000-8000-%012d", n));
    }

    static AdLdapProperties.Ldap settings(String url, String password, int pageSize) {
        AdLdapProperties.Ldap settings = new AdLdapProperties.Ldap();
        settings.setUrl(url);
        settings.setBaseDn(BASE);
        settings.setBindDn(BIND_DN);
        settings.setBindPassword(password);
        settings.setPageSize(pageSize);
        return settings;
    }

    private static LdapAdDirectoryClient client(String password, int pageSize) {
        AdLdapProperties.Ldap settings = settings("ldap://localhost:" + server.getListenPort(), password, pageSize);
        return new LdapAdDirectoryClient(LdapConfig.contextSource(settings), settings);
    }

    private final AdEmployeeMapper mapper = new AdEmployeeMapper(new AdLdapProperties.Ldap().allowedCountries());

    private List<AdEmployee> employees() {
        return mapper.mapAll(client(PASSWORD, 500).fetchAll()).employees();
    }

    private static Set<String> logins(List<AdRecord> records) {
        return records.stream().map(r -> String.valueOf(r.get("sAMAccountName"))).collect(Collectors.toSet());
    }

    // --- fetchAll ------------------------------------------------------------------------------------------------

    @Test
    void realFilterReturnsEnabledCentralServicesUsersWithoutCopilotAccounts() {
        List<AdRecord> records = client(PASSWORD, 500).fetchAll();

        assertThat(logins(records)).hasSize(14)
                .contains("user00", "user09", "minimal", "noguid", "tdev", "noname")
                .doesNotContain("copilot-agent01", "disabled", "auditor", "csvlike", "partner");
    }

    @Test
    void testOuAndBlankDisplayNameEntriesPassThroughClientAndMapperAndAreSkipped() {
        List<AdRecord> records = client(PASSWORD, 500).fetchAll();

        AdEmployeeMapper.Batch batch = assertDoesNotThrow(() -> mapper.mapAll(records));

        assertThat(batch.employees()).hasSize(11);
        assertThat(batch.employees()).extracting(AdEmployee::login).doesNotContain("noguid", "tdev", "noname");
        assertThat(batch.employees()).allSatisfy(e -> assertThat(e.countryCode()).isIn("KZ", "UZ"));
        assertThat(batch.skipped()).containsExactlyInAnyOrderEntriesOf(Map.of(
                SkipReason.COUNTRY_NOT_ALLOWED, 1, SkipReason.BLANK_DISPLAY_NAME, 1, SkipReason.MISSING_REQUIRED, 1));
        assertThat(batch.employees().size() + batch.skippedCount()).isEqualTo(records.size());
    }

    @Test
    void allPagesComeOverOneConnectionWithOneBind() {
        List<AdRecord> records = client(PASSWORD, 3).fetchAll();

        assertThat(records).hasSize(14);
        // 14 matching entries in pages of 3
        assertThat(counter.searches).hasSize(5);
        assertThat(counter.searches).allSatisfy(s -> assertThat(s.paged()).isTrue());
        assertThat(counter.searches.stream().map(Op::connection).distinct()).hasSize(1);
        assertThat(counter.binds).hasSize(1);
        assertThat(counter.binds.get(0).connection()).isEqualTo(counter.searches.get(0).connection());
    }

    @Test
    void objectGuidIsReadAsBinaryAndConverted() {
        assertThat(client(PASSWORD, 500).fetchAll()).filteredOn(r -> "minimal".equals(r.get("sAMAccountName")))
                .singleElement().satisfies(r -> assertThat(r.get("objectGUID")).isInstanceOf(byte[].class));

        AdEmployee minimal = employees().stream()
                .filter(e -> e.login().equals("minimal")).findFirst().orElseThrow();
        assertThat(minimal.adGuid()).isEqualTo(guid(50));
    }

    @Test
    void attributesAreMappedAndMissingOnesAreNull() {
        List<AdEmployee> employees = employees();
        AdEmployee full = employees.stream().filter(e -> e.login().equals("user01")).findFirst().orElseThrow();
        AdEmployee minimal = employees.stream().filter(e -> e.login().equals("minimal")).findFirst().orElseThrow();

        assertThat(full.dn()).isEqualTo("CN=user01,OU=UZ," + BASE);
        assertThat(full.countryCode()).isEqualTo("UZ");
        assertThat(full.department()).isEqualTo("CS / ITS");
        assertThat(full.managerDn()).isEqualTo("CN=partner,OU=Partners," + ROOT);
        assertThat(full.pmUpn()).isEqualTo("partner@kz.example");
        assertThat(full.enabled()).isTrue();
        assertThat(full.adWhenChanged()).isNotNull();

        assertThat(minimal.dn()).isEqualToIgnoringCase("CN=minimal,OU=KZ," + BASE);
        assertThat(minimal.countryCode()).isEqualTo("KZ");
        assertThat(minimal.email()).isNull();
        assertThat(minimal.jobTitle()).isNull();
        assertThat(minimal.managerDn()).isNull();
        assertThat(minimal.pmUpn()).isNull();
        assertThat(minimal.adWhenChanged()).isNull();
    }

    // --- lookups -------------------------------------------------------------------------------------------------

    @Test
    void findByUpnFindsAUserOfAnyDepartment() {
        assertThat(client(PASSWORD, 500).findByUpn("AUDITOR@kz.example"))
                .hasValueSatisfying(r -> assertThat(r.get("department")).isEqualTo("Audit/Financial Services"));
    }

    @Test
    void findByDnOutsideTheBaseDnFallsBackToTheDomainRoot() {
        LdapAdDirectoryClient client = client(PASSWORD, 500);

        AdRecord partner = client.findByDn("CN=partner,OU=Partners," + ROOT).orElseThrow();
        // Outside OU=Countries: as a Central Services employee it would be skipped, as an external it is kept
        assertThat(mapper.map(partner)).isInstanceOf(AdEmployeeMapper.Skipped.class);
        assertThat(mapper.mapExternal(partner)).isInstanceOfSatisfying(AdEmployeeMapper.Mapped.class, m -> {
            assertThat(m.employee().login()).isEqualTo("partner");
            assertThat(m.employee().countryCode()).isNull();
        });
        assertThat(counter.searches).hasSize(2);
        assertThat(counter.searches.get(1).base()).isEqualToIgnoringCase(ROOT);
        assertThat(counter.binds).hasSize(1);
    }

    @Test
    void findOfAnUnknownUserIsEmpty() {
        LdapAdDirectoryClient client = client(PASSWORD, 500);

        assertThat(client.findByUpn("nobody@kz.example")).isEmpty();
        assertThat(client.findByDn("CN=nobody,OU=KZ," + BASE)).isEmpty();
        assertThat(client.findByUpn(" ")).isEmpty();
    }

    @Test
    void filterValuesAreEscaped() {
        assertThat(client(PASSWORD, 500).findByUpn("*")).isEmpty();
    }

    @Test
    void domainRootIsTheDcPartOfTheBaseDn() {
        assertThat(LdapAdDirectoryClient.domainRoot("OU=Countries,OU=CCG,DC=go1,DC=kworld,DC=kpmg,DC=com"))
                .isEqualTo("DC=go1,DC=kworld,DC=kpmg,DC=com");
        assertThat(LdapAdDirectoryClient.domainRoot("not a dn")).isEmpty();
    }

    // --- failures ------------------------------------------------------------------------------------------------

    @Test
    void wrongPasswordIsAuthAfterOneBindAndSuspendsScheduledRuns() {
        AuthCircuitBreaker breaker = new AuthCircuitBreaker();
        LdapAdDirectoryClient client = client("wrong-password", 3);

        assertThatThrownBy(client::fetchAll)
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.AUTH);
                    assertThat(e.getMessage()).contains("LDAP error 49").doesNotContain("wrong-password");
                    breaker.recordFailure(e.getCode(), e.getMessage());
                });
        assertThat(counter.binds).hasSize(1);
        assertThat(counter.searches).isEmpty();

        // The next scheduled and startup runs do not even try
        for (SyncTrigger trigger : List.of(SyncTrigger.SCHEDULED, SyncTrigger.STARTUP, SyncTrigger.SCHEDULED)) {
            if (breaker.tryStart(trigger)) {
                client.fetchAll();
            }
        }
        assertThat(counter.binds).as("no repeated bind attempts").hasSize(1);
    }

    @Test
    void closedPortIsNetwork() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        AdLdapProperties.Ldap settings = settings("ldap://127.0.0.1:" + closed, PASSWORD, 500);
        LdapAdDirectoryClient client = new LdapAdDirectoryClient(LdapConfig.contextSource(settings), settings);

        assertThatThrownBy(client::fetchAll)
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.NETWORK);
                    assertThat(e.getMessage()).contains("127.0.0.1:" + closed, "port 636");
                });
    }

    @Test
    void plainLdapIsRefusedWithoutConnecting() {
        LdapAdDirectoryClient client = LdapConfig.client(
                settings("ldap://localhost:" + server.getListenPort(), PASSWORD, 500));

        assertThatThrownBy(client::fetchAll)
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.CONFIG);
                    assertThat(e.getMessage()).contains("ldaps://");
                });
        assertThatThrownBy(() -> client.findByUpn("user00@kz.example")).isInstanceOf(AdDirectoryException.class);
        assertThat(counter.binds).isEmpty();
    }

    @Test
    void missingSettingsAreNamed() {
        AdLdapProperties.Ldap settings = new AdLdapProperties.Ldap();
        settings.setPageSize(5000);

        assertThatThrownBy(() -> LdapConfig.client(settings).fetchAll())
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.CONFIG);
                    assertThat(e.getMessage()).contains("AD_LDAP_URL is not set", "AD_LDAP_BASE_DN",
                            "AD_LDAP_BIND_DN", "AD_LDAP_BIND_PASSWORD", "AD_LDAP_PAGE_SIZE");
                });
    }

    @Test
    void theServerReceivesTheFilterOfTheSpec() throws LDAPException {
        client(PASSWORD, 500).fetchAll();

        assertThat(Filter.create(counter.searches.get(0).filter()))
                .isEqualTo(Filter.create(AdLdapProperties.DEFAULT_FILTER));
    }

    // --- request counter -----------------------------------------------------------------------------------------

    record Op(long connection, String base, boolean paged, String filter) {
    }

    /**
     * Records binds and searches as the client sent them. The in-memory server has no extensible matching, so the
     * AD bitwise rule "ACCOUNTDISABLE bit set" (userAccountControl:1.2.840.113556.1.4.803:=2) is then evaluated as
     * userAccountControl=514, the only disabled value of the test users.
     */
    static final class Counter extends InMemoryOperationInterceptor {
        static final String AD_BIT_AND = "1.2.840.113556.1.4.803";

        final List<Op> binds = new CopyOnWriteArrayList<>();
        final List<Op> searches = new CopyOnWriteArrayList<>();

        @Override
        public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) {
            binds.add(new Op(request.getConnectionID(), request.getRequest().getBindDN(), false, null));
        }

        @Override
        public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
            searches.add(new Op(request.getConnectionID(), request.getRequest().getBaseDN(),
                    request.getRequest().hasControl(SimplePagedResultsControl.PAGED_RESULTS_OID),
                    request.getRequest().getFilter().toString()));
            SearchRequest evaluated = request.getRequest().duplicate();
            evaluated.setFilter(withoutBitwiseMatch(evaluated.getFilter()));
            request.setRequest(evaluated);
        }

        private static Filter withoutBitwiseMatch(Filter filter) {
            return switch (filter.getFilterType()) {
                case Filter.FILTER_TYPE_AND -> Filter.createANDFilter(
                        Arrays.stream(filter.getComponents()).map(Counter::withoutBitwiseMatch).toList());
                case Filter.FILTER_TYPE_OR -> Filter.createORFilter(
                        Arrays.stream(filter.getComponents()).map(Counter::withoutBitwiseMatch).toList());
                case Filter.FILTER_TYPE_NOT -> Filter.createNOTFilter(withoutBitwiseMatch(filter.getNOTComponent()));
                case Filter.FILTER_TYPE_EXTENSIBLE_MATCH -> AD_BIT_AND.equals(filter.getMatchingRuleID())
                        && "userAccountControl".equalsIgnoreCase(filter.getAttributeName())
                        && "2".equals(filter.getAssertionValue())
                        ? Filter.createEqualityFilter("userAccountControl", "514") : filter;
                default -> filter;
            };
        }

        void reset() {
            binds.clear();
            searches.clear();
        }
    }
}
