package cs_orgchart.ad.mapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.mapper.AdEmployeeMapper.Batch;
import cs_orgchart.ad.mapper.AdEmployeeMapper.Mapped;
import cs_orgchart.ad.mapper.AdEmployeeMapper.Result;
import cs_orgchart.ad.mapper.AdEmployeeMapper.SkipReason;
import cs_orgchart.ad.mapper.AdEmployeeMapper.Skipped;
import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.ad.model.AdRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAP entry → {@link AdEmployee} or a skip (spec v3 §4.3, §4.4): never an exception for a data problem, never a
 * synced employee without an allowed country.
 */
class AdEmployeeMapperTest {

    /** objectGUID as AD returns it: the first three blocks little-endian */
    private static final byte[] GUID_BYTES = HexFormat.of().parseHex("67452301ab89efcd0123456789abcdef");
    private static final UUID GUID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final String KZ_DN = "CN=tuser,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example";

    private final AdEmployeeMapper mapper =
            new AdEmployeeMapper(new AdLdapProperties.Ldap().allowedCountries());

    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(AdEmployeeMapper.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logged);
    }

    // --- conversions ---------------------------------------------------------------------------------------------

    @Test
    void objectGuidBytesBecomeTheUuidAdShows() {
        assertThat(AdEmployeeMapper.guid(GUID_BYTES)).isEqualTo(GUID);
        assertThat(AdEmployeeMapper.guidBytes(GUID)).isEqualTo(GUID_BYTES);
        assertThat(AdEmployeeMapper.guid(GUID.toString())).isEqualTo(GUID);
        UUID random = UUID.randomUUID();
        assertThat(AdEmployeeMapper.guid(AdEmployeeMapper.guidBytes(random))).isEqualTo(random);
    }

    @ParameterizedTest(name = "uac {0} -> enabled {1}")
    @CsvSource({"512, true", "514, false", "66048, true", "66050, false", "2, false", "544, true"})
    void enabledUnlessAccountDisableBitIsSet(String uac, boolean enabled) {
        assertThat(AdEmployeeMapper.enabled(uac)).isEqualTo(enabled);
        assertThat(AdEmployeeMapper.enabled(Integer.parseInt(uac))).isEqualTo(enabled);
    }

    @Test
    void generalizedTimeIsReadInUtcOrWithItsOffset() {
        assertThat(AdEmployeeMapper.generalizedTime("20261023081530.0Z")).isEqualTo(Instant.parse("2026-10-23T08:15:30Z"));
        assertThat(AdEmployeeMapper.generalizedTime("20261023081530Z")).isEqualTo(Instant.parse("2026-10-23T08:15:30Z"));
        assertThat(AdEmployeeMapper.generalizedTime("20261023081530.25+0500"))
                .isEqualTo(Instant.parse("2026-10-23T03:15:30.25Z"));
        assertThat(AdEmployeeMapper.generalizedTime("yesterday")).isNull();
        assertThat(AdEmployeeMapper.generalizedTime(" ")).isNull();
    }

    // --- mapped employee -----------------------------------------------------------------------------------------

    @Test
    void allAttributesAreMappedAndTrimmed() {
        AdEmployee employee = mapped(mapper.map(new AdRecord(full())));

        assertThat(employee.adGuid()).isEqualTo(GUID);
        assertThat(employee.dn()).isEqualTo(KZ_DN);
        assertThat(employee.login()).isEqualTo("tuser");
        assertThat(employee.upn()).isEqualTo("tuser@kz.example");
        assertThat(employee.email()).isEqualTo("test.user@kz.example");
        assertThat(employee.displayName()).isEqualTo("User, Test");
        assertThat(employee.firstName()).isEqualTo("Test");
        assertThat(employee.lastName()).isEqualTo("User");
        assertThat(employee.jobTitle()).isEqualTo("Senior Manager, UZ");
        assertThat(employee.department()).isEqualTo("CS/ITS/ITS/ITS General");
        assertThat(employee.company()).isEqualTo("Mock");
        assertThat(employee.managerDn()).isEqualTo("CN=boss,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example");
        assertThat(employee.pmUpn()).isEqualTo("boss@kz.example");
        assertThat(employee.office()).isEqualTo("Office");
        assertThat(employee.city()).isEqualTo("Almaty");
        assertThat(employee.phone()).isEqualTo("+000 1");
        assertThat(employee.mobile()).isEqualTo("+000 2");
        assertThat(employee.employeeId()).isEqualTo("E1");
        assertThat(employee.enabled()).isTrue();
        assertThat(employee.adWhenChanged()).isEqualTo(Instant.parse("2026-10-23T08:15:30Z"));
        assertThat(employee.countryCode()).isEqualTo("KZ");
    }

    @Test
    void withoutManagerAndPmTheEmployeeIsMappedWithNullLinks() {
        AdEmployee employee = mapped(mapper.map(new AdRecord(required())));

        assertThat(employee.managerDn()).isNull();
        assertThat(employee.pmUpn()).isNull();
    }

    @Test
    void emptyOptionalAttributesAreNull() {
        Map<String, Object> entry = required();
        entry.put("mail", "   ");
        entry.put("title", "");
        entry.put("manager", " ");
        entry.put("kPMG-User-PerformanceManagerUPN", "");

        AdEmployee employee = mapped(mapper.map(new AdRecord(entry)));

        assertThat(employee.email()).isNull();
        assertThat(employee.jobTitle()).isNull();
        assertThat(employee.managerDn()).isNull();
        assertThat(employee.pmUpn()).isNull();
        assertThat(employee.department()).isNull();
        assertThat(employee.adWhenChanged()).isNull();
    }

    @Test
    void attributeNamesAreCaseInsensitive() {
        Map<String, Object> entry = required();
        entry.remove("sAMAccountName");
        entry.put("samaccountname", "lower");

        assertThat(mapped(mapper.map(new AdRecord(entry))).login()).isEqualTo("lower");
    }

    // --- rule 1: country whitelist -------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            CN=u,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=go1,DC=example | KZ
            CN=u,OU=Users,OU=UZ,OU=Countries,OU=CCG,DC=go1,DC=example           | UZ
            CN=u,OU=AZ,OU=Countries,OU=CCG,DC=go1,DC=example                    | AZ
            CN=u,OU=Tbilisi,OU=GE,OU=Countries,OU=CCG,DC=go1,DC=example         | GE
            CN=u,OU=AM,OU=Countries,OU=CCG,DC=go1,DC=example                    | AM
            CN=u,OU=KG,OU=Countries,OU=CCG,DC=go1,DC=example                    | KG
            cn=u,ou=Users,ou=kz,ou=countries,ou=CCG,dc=go1,dc=example           | KZ
            CN=Last\\, First,OU=Users,OU=Uz,OU=Countries,OU=CCG,DC=go1,DC=example | UZ
            """)
    void allowedCountryIsTakenFromTheOuBeforeCountriesInUpperCase(String dn, String country) {
        assertThat(mapped(mapper.map(record(dn))).countryCode()).isEqualTo(country);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "CN=tdev,OU=Test for Dev Team,OU=Countries,OU=CCG,DC=go1,DC=example",
            "CN=u,OU=RU,OU=Countries,OU=CCG,DC=go1,DC=example",
            "CN=svc,OU=Service Accounts,OU=CCG,DC=go1,DC=example",
            "CN=u,OU=KZ,OU=CCG,DC=go1,DC=example",
            "CN=u,OU=Countries,OU=CCG,DC=go1,DC=example",
            "CN=u,CN=KZ,OU=Countries,OU=CCG,DC=go1,DC=example",
            "not a distinguished name"})
    void noAllowedCountryOuIsSkipped(String dn) {
        Result result = mapper.map(record(dn));

        assertThat(result).isInstanceOfSatisfying(Skipped.class,
                s -> assertThat(s.reason()).isEqualTo(SkipReason.COUNTRY_NOT_ALLOWED));
    }

    @Test
    void customWhitelistIsUsed() {
        AdLdapProperties.Ldap settings = new AdLdapProperties.Ldap();
        settings.setCountries(" kz , ,ru,");
        AdEmployeeMapper kzAndRu = new AdEmployeeMapper(settings.allowedCountries());

        assertThat(kzAndRu.allowedCountries()).containsExactly("KZ", "RU");
        assertThat(mapped(kzAndRu.map(record("CN=u,OU=RU,OU=Countries,OU=CCG,DC=go1,DC=example"))).countryCode())
                .isEqualTo("RU");
        assertThat(kzAndRu.map(record("CN=u,OU=UZ,OU=Countries,OU=CCG,DC=go1,DC=example")))
                .isInstanceOf(Skipped.class);
    }

    @Test
    void blankWhitelistFallsBackToTheDefault() {
        AdLdapProperties.Ldap settings = new AdLdapProperties.Ldap();
        settings.setCountries(" , ");

        assertThat(settings.allowedCountries()).containsExactly("KZ", "UZ", "AZ", "GE", "AM", "KG");
    }

    @Test
    void aMappedEmployeeNeverHasANullCountry() {
        List<String> dns = List.of(KZ_DN, "CN=u,OU=Test for Dev Team,OU=Countries,DC=x", "CN=u,DC=x", "",
                "CN=u,OU=,OU=Countries,DC=x", "CN=u,OU=AM,OU=Countries,DC=x", "CN=u,OU=Countries,DC=x");
        for (String dn : dns) {
            Result result = mapper.map(record(dn));
            if (result instanceof Mapped m) {
                assertThat(m.employee().countryCode()).as(dn).isNotNull().isIn(mapper.allowedCountries());
            }
        }
    }

    // --- rule 2: displayName -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "displayName [{0}]")
    @NullSource
    @ValueSource(strings = {"", " ", "   \t "})
    void blankDisplayNameIsSkipped(String displayName) {
        Map<String, Object> entry = required();
        if (displayName == null) {
            entry.remove("displayName");
        } else {
            entry.put("displayName", displayName);
        }

        assertThat(mapper.map(new AdRecord(entry))).isEqualTo(
                new Skipped(SkipReason.BLANK_DISPLAY_NAME, GUID.toString(),
                        "OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example"));
    }

    // --- rule 3: other required values ---------------------------------------------------------------------------

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {"objectGUID", "userPrincipalName", "sAMAccountName", "userAccountControl"})
    void missingRequiredValueIsSkipped(String attribute) {
        Map<String, Object> entry = required();
        entry.remove(attribute);

        assertThat(mapper.map(new AdRecord(entry))).isInstanceOfSatisfying(Skipped.class,
                s -> assertThat(s.reason()).isEqualTo(SkipReason.MISSING_REQUIRED));
    }

    @Test
    void missingDnIsMissingRequired() {
        Map<String, Object> entry = required();
        entry.remove("distinguishedName");

        assertThat(mapper.map(new AdRecord(entry)))
                .isEqualTo(new Skipped(SkipReason.MISSING_REQUIRED, GUID.toString(), "n/a"));
    }

    @Test
    void unusableValuesAreMissingRequiredNotExceptions() {
        Map<String, Object> badGuid = required();
        badGuid.put("objectGUID", new byte[15]);
        Map<String, Object> badGuidText = required();
        badGuidText.put("objectGUID", "not-a-guid");
        Map<String, Object> badUac = required();
        badUac.put("userAccountControl", "enabled");

        for (Map<String, Object> entry : List.of(badGuid, badGuidText, badUac)) {
            assertThatCode(() -> mapper.map(new AdRecord(entry))).doesNotThrowAnyException();
            assertThat(mapper.map(new AdRecord(entry))).isInstanceOfSatisfying(Skipped.class,
                    s -> assertThat(s.reason()).isEqualTo(SkipReason.MISSING_REQUIRED));
        }
        assertThat(((Skipped) mapper.map(new AdRecord(badGuid))).guid()).isEqualTo("n/a");
    }

    @Test
    void anEmptyRecordIsSkippedNotThrown() {
        assertThat(mapper.map(new AdRecord(Map.of()))).isInstanceOf(Skipped.class);
        assertThat(mapper.mapExternal(new AdRecord(Map.of()))).isInstanceOf(Skipped.class);
    }

    @Test
    void theRawGuidHelperStillRejectsWrongLengths() {
        assertThatThrownBy(() -> AdEmployeeMapper.guid(new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    // --- externals -----------------------------------------------------------------------------------------------

    @Test
    void externalIsExemptFromTheCountryRuleAndHasNoCountry() {
        AdEmployee partner = mapped(mapper.mapExternal(record("CN=partner,OU=Partners,DC=corp,DC=example")));
        AdEmployee kzPartner = mapped(mapper.mapExternal(record(KZ_DN)));

        assertThat(partner.countryCode()).isNull();
        assertThat(kzPartner.countryCode()).isNull();
    }

    @Test
    void externalStillNeedsDisplayNameAndRequiredValues() {
        Map<String, Object> noName = required();
        noName.put("distinguishedName", "CN=partner,OU=Partners,DC=corp,DC=example");
        noName.put("displayName", " ");
        Map<String, Object> noUpn = required();
        noUpn.put("distinguishedName", "CN=partner,OU=Partners,DC=corp,DC=example");
        noUpn.remove("userPrincipalName");

        assertThat(((Skipped) mapper.mapExternal(new AdRecord(noName))).reason()).isEqualTo(SkipReason.BLANK_DISPLAY_NAME);
        assertThat(((Skipped) mapper.mapExternal(new AdRecord(noUpn))).reason()).isEqualTo(SkipReason.MISSING_REQUIRED);
    }

    // --- batch, counting and logging -----------------------------------------------------------------------------

    @Test
    void batchReturnsTheMappedEmployeesAndTheSkipsPerReason() {
        Map<String, Object> blank = required();
        blank.put("displayName", "");
        Map<String, Object> noLogin = required();
        noLogin.remove("sAMAccountName");

        Batch batch = mapper.mapAll(List.of(new AdRecord(full()),
                record("CN=t,OU=Test for Dev Team,OU=Countries,OU=CCG,DC=corp,DC=example"),
                new AdRecord(blank), new AdRecord(noLogin), new AdRecord(required())));

        assertThat(batch.employees()).hasSize(2);
        assertThat(batch.skipped()).containsExactlyInAnyOrderEntriesOf(Map.of(
                SkipReason.COUNTRY_NOT_ALLOWED, 1, SkipReason.BLANK_DISPLAY_NAME, 1, SkipReason.MISSING_REQUIRED, 1));
        assertThat(batch.skippedCount()).isEqualTo(3);
    }

    @Test
    void cleanBatchHasZeroForEveryReasonAndLogsNothing() {
        Batch batch = mapper.mapAll(List.of(new AdRecord(full())));

        assertThat(batch.skipped()).containsOnlyKeys(SkipReason.values()).allSatisfy((r, n) -> assertThat(n).isZero());
        assertThat(logged.list).isEmpty();
    }

    @Test
    void warningsNameReasonGuidAndOuButNoNamesOrEmails() {
        Map<String, Object> blank = full();
        blank.put("displayName", " ");
        Map<String, Object> testOu = full();
        testOu.put("distinguishedName", "CN=Seytbekov\\, Test,OU=Test for Dev Team,OU=Countries,OU=CCG,DC=corp,DC=example");

        mapper.mapAll(List.of(new AdRecord(blank), new AdRecord(testOu)));

        List<String> lines = messages();
        assertThat(lines).containsExactly(
                "Skipped AD record: reason=BLANK_DISPLAY_NAME, guid=" + GUID
                        + ", ou=OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example",
                "Skipped AD record: reason=COUNTRY_NOT_ALLOWED, guid=" + GUID
                        + ", ou=OU=Test for Dev Team,OU=Countries,OU=CCG,DC=corp,DC=example",
                "Skipped AD records in this run: 2 (COUNTRY_NOT_ALLOWED=1, BLANK_DISPLAY_NAME=1)");
        assertThat(String.join("\n", lines))
                .doesNotContain("tuser", "Seytbekov", "User, Test", "test.user@", "@kz.example", "boss");
        assertThat(logged.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void atMostTwentySkipsAreLoggedThenOneSummary() {
        List<AdRecord> records = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            records.add(record("CN=u" + i + ",OU=Test for Dev Team,OU=Countries,OU=CCG,DC=corp,DC=example"));
        }

        Batch batch = mapper.mapAll(records);

        assertThat(batch.skippedCount()).isEqualTo(25);
        List<String> lines = messages();
        assertThat(lines).hasSize(21);
        assertThat(lines.subList(0, 20)).allSatisfy(l -> assertThat(l).startsWith("Skipped AD record: "));
        assertThat(lines.get(20)).isEqualTo(
                "Skipped AD records in this run: 25 (COUNTRY_NOT_ALLOWED=25); only the first 20 are listed");
    }

    @Test
    void runCountsExternalsTooAndFinishesOnce() {
        AdEmployeeMapper.Run run = mapper.newRun();
        run.map(record("CN=t,OU=Test for Dev Team,OU=Countries,OU=CCG,DC=corp,DC=example"));
        Map<String, Object> external = required();
        external.put("displayName", "");
        run.mapExternal(new AdRecord(external));
        run.finish();
        run.finish();

        assertThat(run.skippedCount()).isEqualTo(2);
        assertThat(messages()).filteredOn(l -> l.startsWith("Skipped AD records in this run")).hasSize(1);
    }

    @Test
    void ouIsTheDnWithoutItsCnPart() {
        assertThat(AdEmployeeMapper.ou(KZ_DN)).isEqualTo("OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example");
        assertThat(AdEmployeeMapper.ou("CN=Last\\, First,OU=KZ,DC=x")).isEqualTo("OU=KZ,DC=x");
        assertThat(AdEmployeeMapper.ou("CN=only")).isEqualTo("n/a");
        assertThat(AdEmployeeMapper.ou("garbage")).isEqualTo("n/a");
        assertThat(AdEmployeeMapper.ou(null)).isEqualTo("n/a");
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static AdEmployee mapped(Result result) {
        assertThat(result).isInstanceOf(Mapped.class);
        return ((Mapped) result).employee();
    }

    private static AdRecord record(String dn) {
        Map<String, Object> entry = required();
        entry.put("distinguishedName", dn);
        return new AdRecord(entry);
    }

    private static Map<String, Object> required() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("objectGUID", GUID_BYTES);
        entry.put("distinguishedName", KZ_DN);
        entry.put("sAMAccountName", "tuser");
        entry.put("userPrincipalName", "tuser@kz.example");
        entry.put("displayName", "User, Test");
        entry.put("userAccountControl", "512");
        return entry;
    }

    private static Map<String, Object> full() {
        Map<String, Object> entry = required();
        entry.put("displayName", "  User, Test ");
        entry.put("mail", "test.user@kz.example");
        entry.put("givenName", "Test");
        entry.put("sn", "User");
        entry.put("title", "Senior Manager, UZ");
        entry.put("department", "CS/ITS/ITS/ITS General");
        entry.put("company", "Mock");
        entry.put("manager", "CN=boss,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example");
        entry.put("kPMG-User-PerformanceManagerUPN", "boss@kz.example");
        entry.put("physicalDeliveryOfficeName", "Office");
        entry.put("l", "Almaty");
        entry.put("telephoneNumber", "+000 1");
        entry.put("mobile", "+000 2");
        entry.put("employeeID", "E1");
        entry.put("whenChanged", "20261023081530.0Z");
        return entry;
    }
}
