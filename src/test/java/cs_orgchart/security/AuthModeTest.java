package cs_orgchart.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthModeTest {

    private static final String HASH = "$2a$10$" + "b".repeat(53);

    private static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        return env;
    }

    @Test
    void byDefault_thePasswordForm_withLogout() {
        AuthMode mode = new AuthMode(env().withProperty(AuthMode.PASSWORD_USERS, "admin:" + HASH));
        assertThat(mode.password()).isTrue();
        assertThat(mode.sso()).isFalse();
        assertThat(mode.logout()).isTrue();
    }

    @Test
    void sso_turnsThePasswordFormAndLogoutOff() {
        AuthMode mode = new AuthMode(env().withProperty(AuthMode.SSO, "true"));
        assertThat(mode.sso()).isTrue();
        assertThat(mode.password()).isFalse();
        assertThat(mode.logout()).isFalse();
    }

    @Test
    void emptyValues_countAsNotGiven() {
        AuthMode mode = new AuthMode(env().withProperty(AuthMode.SSO, "true")
                .withProperty(AuthMode.PASSWORD, " ").withProperty(AuthMode.LOGOUT, ""));
        assertThat(mode.sso()).isTrue();
        assertThat(mode.logout()).isFalse();
    }

    @Test
    void logout_canBeSwitchedOnWithSso() {
        assertThat(new AuthMode(env().withProperty(AuthMode.SSO, "true").withProperty(AuthMode.LOGOUT, "true"))
                .logout()).isTrue();
    }

    @Test
    void bothOn_isRefused() {
        assertThat(AuthMode.refusal(env().withProperty(AuthMode.SSO, "true").withProperty(AuthMode.PASSWORD, "TRUE")))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("AUTH_SSO_ENABLED and AUTH_PASSWORD_ENABLED are both true"));
    }

    @Test
    void bothOff_isRefused() {
        assertThat(AuthMode.refusal(env().withProperty(AuthMode.SSO, "false").withProperty(AuthMode.PASSWORD, "false")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("both false: nobody could sign in"));
    }

    @Test
    void notABoolean_isRefused() {
        assertThat(AuthMode.refusal(env().withProperty(AuthMode.SSO, "yes")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("auth.sso.enabled must be true or false"));
    }

    @Test
    void thePasswordForm_withoutAccounts_isRefused_exceptInLocal() {
        assertThat(AuthMode.refusal(env())).hasValueSatisfying(reason ->
                assertThat(reason).contains("AUTH_PASSWORD_USERS is empty"));
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThat(AuthMode.refusal(local)).isEmpty();
    }

    @Test
    void aPlainPassword_isRefused_withoutShowingIt() {
        assertThat(AuthMode.refusal(env().withProperty(AuthMode.PASSWORD_USERS, "admin:Secret123")))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("\"admin\" is not a bcrypt hash").doesNotContain("Secret123"));
    }

    @Test
    void theConstructor_failsWithTheSameReason() {
        assertThatThrownBy(() -> new AuthMode(env().withProperty(AuthMode.SSO, "false")
                .withProperty(AuthMode.PASSWORD, "false")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("both false");
    }
}
