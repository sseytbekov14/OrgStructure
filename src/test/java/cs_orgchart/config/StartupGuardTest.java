package cs_orgchart.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StartupGuardTest {

    private static final String HASH = "$2a$10$" + "a".repeat(53);

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void noProfile_isRefused() {
        assertThat(StartupGuard.refusal(env())).hasValueSatisfying(reason ->
                assertThat(reason).contains("choose the profile explicitly").contains("prod, stage or local"));
    }

    @Test
    void anUnknownProfileAlone_isRefused_andNamed() {
        assertThat(StartupGuard.refusal(env("default"))).hasValueSatisfying(reason ->
                assertThat(reason).contains("choose the profile explicitly").contains("(active: default)"));
    }

    @Test
    void twoDeploymentProfiles_areRefused() {
        assertThat(StartupGuard.refusal(env("prod", "local"))).hasValueSatisfying(reason ->
                assertThat(reason).contains("only one of the profiles"));
    }

    @Test
    void local_startsWithoutAnyVariable() {
        assertThat(StartupGuard.refusal(env("local"))).isEmpty();
    }

    @Test
    void stage_startsOpen_withoutAnyVariable() {
        assertThat(StartupGuard.refusal(env("stage"))).isEmpty();
    }

    @Test
    void stage_withThePasswordForm_needsAccounts() {
        MockEnvironment password = env("stage").withProperty("auth.password.enabled", "true");
        assertThat(StartupGuard.refusal(password)).hasValueSatisfying(reason ->
                assertThat(reason).contains("AUTH_PASSWORD_USERS is empty"));
        assertThat(StartupGuard.refusal(password.withProperty("auth.password.users", "admin:" + HASH))).isEmpty();
    }

    @Test
    void theSignInMode_isCheckedToo() {
        MockEnvironment env = env("local")
                .withProperty("auth.sso.enabled", "true")
                .withProperty("auth.password.enabled", "true");
        assertThat(StartupGuard.refusal(env)).hasValueSatisfying(reason ->
                assertThat(reason).contains("both true"));
    }
}
