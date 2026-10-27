package cs_orgchart.ad.config;

import com.unboundid.ldap.sdk.Filter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults of the AD_LDAP_* settings (spec v3 §4.2, §8).
 */
class AdLdapPropertiesTest {

    /** The one-line filter of spec §4.2 */
    private static final String SPEC_FILTER = "(&(objectCategory=person)(objectClass=user)(|(department=Central"
            + " Services*)(department=CS/*)(department=CS /*)(department=CS))(!(userAccountControl:1.2.840.113556"
            + ".1.4.803:=2))(!(sAMAccountName=copilot-*)))";

    @Test
    void defaultFilterIsTheFilterOfTheSpecIncludingTheCopilotClause() throws Exception {
        assertThat(AdLdapProperties.DEFAULT_FILTER).isEqualTo(SPEC_FILTER).contains("(!(sAMAccountName=copilot-*))");
        assertThat(new AdLdapProperties.Ldap().effectiveFilter()).isEqualTo(SPEC_FILTER);
        Filter.create(SPEC_FILTER);
    }

    @Test
    void ownFilterReplacesTheDefault() {
        AdLdapProperties.Ldap settings = new AdLdapProperties.Ldap();
        settings.setFilter(" (objectClass=user) ");

        assertThat(settings.effectiveFilter()).isEqualTo("(objectClass=user)");
    }

    @Test
    void defaultCountriesAreTheSixCountryOus() {
        assertThat(new AdLdapProperties.Ldap().allowedCountries()).containsExactly("KZ", "UZ", "AZ", "GE", "AM", "KG");
    }

    @Test
    void countriesAreTrimmedUpperCasedAndBlanksIgnored() {
        AdLdapProperties.Ldap settings = new AdLdapProperties.Ldap();
        settings.setCountries(" kz,Uz , ,ge,,");

        assertThat(settings.allowedCountries()).containsExactly("KZ", "UZ", "GE");
    }
}
