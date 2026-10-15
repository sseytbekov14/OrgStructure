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

    @Test
    void withNeitherSignInMode_itDoesNotStart() {
        assertThatThrownBy(() -> run("--spring.profiles.active=test",
                "--auth.sso.enabled=false", "--auth.password.enabled=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both false: nobody could sign in");
    }

    @Test
    void stage_withoutPasswordAccounts_doesNotStart() {
        assertThatThrownBy(() -> run("--spring.profiles.active=stage", "--auth.password.users="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_PASSWORD_USERS is empty");
    }
}
