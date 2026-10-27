package cs_orgchart.config;

import cs_orgchart.security.AuthMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Refuses to start with settings that would quietly run the wrong thing: no explicit profile (prod, stage or
 * local; test for the tests), more than one of them, or a sign-in mode nobody can use (AuthMode). Runs after the
 * configuration files are read (registered in META-INF/spring.factories), so it sees the profiles and the values
 * of the profile files and the environment.
 */
public class StartupGuard implements EnvironmentPostProcessor, Ordered {

    static final List<String> DEPLOYMENT_PROFILES = List.of("prod", "stage", "local");
    static final String TEST_PROFILE = "test";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        refusal(environment).ifPresent(reason -> {
            throw new IllegalStateException("OrgChart does not start: " + reason);
        });
    }

    /** Why these settings cannot start, or empty when they can. */
    static Optional<String> refusal(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        long deployment = active.stream().filter(DEPLOYMENT_PROFILES::contains).count();
        if (deployment == 0 && !active.contains(TEST_PROFILE)) {
            return Optional.of("choose the profile explicitly: SPRING_PROFILES_ACTIVE=prod, stage or local"
                    + (active.isEmpty() ? "" : " (active: " + String.join(",", active) + ")"));
        }
        if (deployment > 1) {
            return Optional.of("only one of the profiles prod, stage and local can be active (active: "
                    + String.join(",", active) + ")");
        }
        // test alone: the accounts come from the test classes, the AuthMode bean checks them
        return AuthMode.refusal(environment, deployment > 0);
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10): the profiles and files are known
        return Ordered.LOWEST_PRECEDENCE;
    }
}
