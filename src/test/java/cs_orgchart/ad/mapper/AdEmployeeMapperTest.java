package cs_orgchart.ad.mapper;

import cs_orgchart.ad.model.AdEmployee;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAP attributes → {@link AdEmployee} (spec v2.1 §4.3).
 */
class AdEmployeeMapperTest {

    /** objectGUID as AD returns it: the first three blocks little-endian */
    private static final byte[] GUID_BYTES = HexFormat.of().parseHex("67452301ab89efcd0123456789abcdef");
    private static final UUID GUID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test
    void objectGuidBytesBecomeTheUuidAdShows() {
        assertThat(AdEmployeeMapper.guid(GUID_BYTES)).isEqualTo(GUID);
        assertThat(AdEmployeeMapper.guidBytes(GUID)).isEqualTo(GUID_BYTES);
        assertThat(AdEmployeeMapper.guid(GUID.toString())).isEqualTo(GUID);
        UUID random = UUID.randomUUID();
        assertThat(AdEmployeeMapper.guid(AdEmployeeMapper.guidBytes(random))).isEqualTo(random);
    }

    @Test
    void objectGuidOfAnotherLengthIsRejected() {
        assertThatThrownBy(() -> AdEmployeeMapper.guid(new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "uac {0} -> enabled {1}")
    @CsvSource({"512, true", "514, false", "66048, true", "66050, false", "2, false", "544, true"})
    void enabledUnlessAccountDisableBitIsSet(String uac, boolean enabled) {
        assertThat(AdEmployeeMapper.enabled(uac)).isEqualTo(enabled);
        assertThat(AdEmployeeMapper.enabled(Integer.parseInt(uac))).isEqualTo(enabled);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            CN=user,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=go1,DC=example | KZ
            CN=user,OU=Users,OU=uz,OU=Countries,OU=CCG,DC=go1,DC=example           | UZ
            CN=user,OU=KG,OU=Countries,OU=CCG,DC=go1,DC=example                    | KG
            cn=user,ou=Users,ou=GE,ou=countries,ou=CCG,dc=go1,dc=example           | GE
            CN=svc,OU=Service Accounts,OU=CCG,DC=go1,DC=example                    | -
            CN=user,OU=Test for Dev Team,OU=CCG,DC=go1,DC=example                  | -
            """)
    void countryFromTheOuUnderCountries(String dn, String country) {
        assertThat(AdEmployeeMapper.countryCode(dn)).isEqualTo(country);
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

    @Test
    void allAttributesAreMappedAndTrimmed() {
        AdEmployee employee = AdEmployeeMapper.map(full());

        assertThat(employee.adGuid()).isEqualTo(GUID);
        assertThat(employee.dn()).isEqualTo("CN=tuser,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example");
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
    void missingAndEmptyOptionalAttributesAreNull() {
        Map<String, Object> entry = required();
        entry.put("mail", "   ");
        entry.put("title", "");

        AdEmployee employee = AdEmployeeMapper.map(entry);

        assertThat(employee.email()).isNull();
        assertThat(employee.jobTitle()).isNull();
        assertThat(employee.department()).isNull();
        assertThat(employee.managerDn()).isNull();
        assertThat(employee.pmUpn()).isNull();
        assertThat(employee.adWhenChanged()).isNull();
        assertThat(employee.phone()).isNull();
    }

    @Test
    void attributeNamesAreCaseInsensitive() {
        Map<String, Object> entry = required();
        entry.remove("sAMAccountName");
        entry.put("samaccountname", "lower");

        assertThat(AdEmployeeMapper.map(entry).login()).isEqualTo("lower");
    }

    @Test
    void entryWithoutARequiredAttributeIsRejectedWithItsDnAndTheMissingNames() {
        Map<String, Object> entry = required();
        entry.remove("objectGUID");
        entry.put("userPrincipalName", " ");

        assertThatThrownBy(() -> AdEmployeeMapper.map(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CN=tuser,OU=Almaty")
                .hasMessageContaining("objectGUID, userPrincipalName");
    }

    private static Map<String, Object> required() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("objectGUID", GUID_BYTES);
        entry.put("distinguishedName", "CN=tuser,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example");
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
