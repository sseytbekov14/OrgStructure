package cs_orgchart.config;

import cs_orgchart.CsOrgchartApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application itself refuses to start with settings nobody can use. The command line wins over a
 * SPRING_PROFILES_ACTIVE of the machine (CI sets local), so each case states the profiles it means.
 */
class ApplicationStartTest {

    private static void run(String... args) {
        SpringApplication app = new SpringApplication(CsOrgchartApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args).close();
    }

    @Test
    void withoutAProfile_itDoesNotStart() {
        assertThatThrownBy(() -> run("--spring.profiles.active="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OrgChart does not start: choose the profile explicitly");
    }

    @Test
    void withBothSignInModes_itDoesNotStart() {
        assertThatThrownBy(() -> run("--spring.profiles.active=test",
                "--auth.sso.enabled=true", "--auth.password.enabled=true"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_SSO_ENABLED and AUTH_PASSWORD_ENABLED are both true");
    }

    /** No database or data files needed: the start fails before they matter, or does not touch them. */
    private static final String[] IN_MEMORY = {
            "--spring.datasource.url=jdbc:h2:mem:start-test;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver",
            "--spring.datasource.username=sa",
            "--spring.datasource.password=",
            "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "--app.data.excel-path=target/test-data/no-such.xlsx",
            "--app.data.photos-path=target/test-data/photos"
    };

    private static String[] inMemory(String... args) {
        String[] all = new String[IN_MEMORY.length + args.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(IN_MEMORY, 0, all, args.length, IN_MEMORY.length);
        return all;
    }

    @Test
    void prod_isSsoByDefault_andDoesNotStartWithoutTheSamlSettings() {
        assertThatThrownBy(() -> run(inMemory("--spring.profiles.active=prod")))
                .hasStackTraceContaining("SSO is on (AUTH_SSO_ENABLED=true) but SAML_ENTITY_ID is not set")
                .hasStackTraceContaining("SAML_ACS_URL is not set")
                .hasStackTraceContaining("SAML_METADATA_URI is not set");
    }

    @Test
    void prod_withUnreadableMetadata_doesNotStart() {
        assertThatThrownBy(() -> run(inMemory("--spring.profiles.active=prod",
                "--auth.sso.entity-id=https://site.example",
                "--auth.sso.acs-url=https://site.example/login/saml2/sso/entra",
                "--auth.sso.metadata-uri=file:target/test-data/no-such-metadata.xml")))
                .hasStackTraceContaining("the Entra metadata of SAML_METADATA_URI cannot be read");
    }

    @Test
    void stage_withThePasswordFormButNoAccounts_doesNotStart() {
        assertThatThrownBy(() -> run("--spring.profiles.active=stage", "--auth.password.enabled=true",
                "--auth.password.users="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_PASSWORD_USERS is empty");
    }
}
