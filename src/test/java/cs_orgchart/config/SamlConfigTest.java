package cs_orgchart.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SamlConfigTest {

    private static final String ACS = "https://site.example/login/saml2/sso/entra";

    @Test
    void allThree_areNeeded() {
        assertThat(SamlConfig.problems("", " ", null)).containsExactly(
                "SAML_ENTITY_ID is not set (the Identifier of the Entra application)",
                "SAML_ACS_URL is not set (the Reply URL, https://<site>/login/saml2/sso/entra)",
                "SAML_METADATA_URI is not set (the App Federation Metadata Url of the Entra application)");
    }

    @Test
    void goodValues_haveNoProblems() {
        assertThat(SamlConfig.problems("https://site.example", ACS, "https://login.example/metadata.xml")).isEmpty();
    }

    @Test
    void theEntityId_isNotATemplate() {
        assertThat(SamlConfig.problems("{baseUrl}/saml2/service-provider-metadata/{registrationId}", ACS, "file:x"))
                .containsExactly("SAML_ENTITY_ID must be the Identifier itself, not a template");
    }

    @Test
    void theAcs_isAbsolute_andEndsWithTheRegistration() {
        assertThat(SamlConfig.problems("id", "/login/saml2/sso/entra", "file:x")).singleElement()
                .asString().contains("must be an absolute address");
        assertThat(SamlConfig.problems("id", "https://site.example/login/saml2/sso/other", "file:x")).singleElement()
                .asString().contains("must end with /login/saml2/sso/entra");
    }
}
