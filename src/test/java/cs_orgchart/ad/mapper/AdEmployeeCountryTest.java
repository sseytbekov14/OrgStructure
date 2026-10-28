package cs_orgchart.ad.mapper;

import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.mapper.AdEmployeeMapper.Mapped;
import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.ad.model.AdRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U6: the AD attributes co (Country/Region name) and c (two-letter code). Trimmed, co at most 128 characters, blank
 * to null, c upper case and exactly two letters else null; no record is ever skipped because of them. country_code
 * (from the DN) is unchanged.
 */
class AdEmployeeCountryTest {

    private static final String KZ_DN = "CN=tuser,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example";

    private final AdEmployeeMapper mapper = new AdEmployeeMapper(new AdLdapProperties.Ldap().allowedCountries());

    private static Map<String, Object> entry() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("objectGUID", HexFormat.of().parseHex("67452301ab89efcd0123456789abcdef"));
        entry.put("distinguishedName", KZ_DN);
        entry.put("sAMAccountName", "tuser");
        entry.put("userPrincipalName", "tuser@kz.example");
        entry.put("displayName", "User, Test");
        entry.put("department", "CS/ITS");
        entry.put("userAccountControl", "512");
        return entry;
    }

    private AdEmployee map(Object co, Object c) {
        Map<String, Object> entry = entry();
        if (co != null) {
            entry.put("co", co);
        }
        if (c != null) {
            entry.put("c", c);
        }
        var result = mapper.map(new AdRecord(entry));
        assertThat(result).as("never skipped because of co / c").isInstanceOf(Mapped.class);
        return ((Mapped) result).employee();
    }

    @Test
    void theNewAttributesAreRequested() {
        assertThat(Arrays.asList(AdEmployeeMapper.AD_ATTRIBUTES)).contains("co", "c", "l");
    }

    @ParameterizedTest(name = "co [{0}] -> [{1}]")
    @CsvSource(value = {"Kazakhstan|Kazakhstan", "  Uzbekistan  |Uzbekistan", "'   '|NULL", "''|NULL"},
            delimiter = '|', nullValues = "NULL")
    void countryNameIsTrimmedAndBlankIsNull(String co, String expected) {
        assertThat(map(co, null).countryName()).isEqualTo(expected);
    }

    @Test
    void aTooLongCountryNameIsCutTo128() {
        assertThat(map("x".repeat(300), null).countryName()).hasSize(128);
        assertThat(map("x".repeat(128), null).countryName()).hasSize(128);
    }

    @ParameterizedTest(name = "c [{0}] -> [{1}]")
    @CsvSource(value = {"KZ|KZ", "kz|KZ", " gb |GB", "KAZ|NULL", "K|NULL", "1A|NULL", "'  '|NULL", "K-|NULL"},
            delimiter = '|', nullValues = "NULL")
    void countryIsoIsTwoLettersUpperCaseElseNull(String c, String expected) {
        assertThat(map(null, c).countryIso()).isEqualTo(expected);
    }

    @Test
    void theAttributeNamesIgnoreCaseAndTheDnCountryStays() {
        Map<String, Object> entry = entry();
        entry.put("CO", "Kazakhstan");
        entry.put("C", "kz");
        AdEmployee employee = ((Mapped) mapper.map(new AdRecord(entry))).employee();
        assertThat(employee.countryName()).isEqualTo("Kazakhstan");
        assertThat(employee.countryIso()).isEqualTo("KZ");
        assertThat(employee.countryCode()).isEqualTo("KZ");
    }

    @Test
    void missingAttributesAreNull() {
        AdEmployee employee = map(null, null);
        assertThat(employee.countryName()).isNull();
        assertThat(employee.countryIso()).isNull();
    }
}
