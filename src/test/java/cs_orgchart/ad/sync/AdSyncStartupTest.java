package cs_orgchart.ad.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.CsOrgchartApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the AD sync does after the start of the application (spec v3 §7, AD_SYNC_ON_STARTUP, AD_SYNC_STARTUP_DRY_RUN):
 * each test starts a second application on the test database with its own AD settings.
 */
@SpringBootTest
@ActiveProfiles("local")
class AdSyncStartupTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM employee_overrides");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM ad_sync_log");
    }

    @Test
    void runningRowLeftByACrashIsMarkedInterruptedAtStartup() {
        jdbc.update("INSERT INTO ad_sync_log (status, trigger_type) VALUES ('RUNNING', 'SCHEDULED')");

        try (ConfigurableApplicationContext app = start("--AD_SYNC_MODE=mock", "--AD_SYNC_ENABLED=true",
                "--AD_SYNC_CRON=-", "--AD_SYNC_ON_STARTUP=false")) {
            await(() -> count("SELECT count(*) FROM ad_sync_log WHERE status = 'FAILED'") == 1);

            assertThat(jdbc.queryForObject("SELECT error_code FROM ad_sync_log", String.class))
                    .isEqualTo("INTERRUPTED");
            assertThat(count("SELECT count(*) FROM employees")).as("no sync on startup").isZero();
        }
    }

    @Test
    void applicationStartsAndKeepsRunningWhileTheStartupSyncCannotReachAd() throws Exception {
        // Accepts the connection (the operating system does) but never answers: the bind waits for the timeouts
        try (ServerSocket silentDc = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            try (ConfigurableApplicationContext app = start("--AD_SYNC_MODE=ldap", "--AD_SYNC_ENABLED=true",
                    "--AD_SYNC_CRON=-", "--AD_SYNC_ON_STARTUP=true",
                    "--AD_LDAP_URL=ldaps://127.0.0.1:" + silentDc.getLocalPort(),
                    "--AD_LDAP_BASE_DN=OU=Countries,OU=CCG,DC=corp,DC=example",
                    "--AD_LDAP_BIND_DN=svc-orgchart@corp.example", "--AD_LDAP_BIND_PASSWORD=test-only-secret",
                    "--ad.ldap.connect-timeout=3s", "--ad.ldap.read-timeout=3s")) {
                assertThat(app.isActive()).isTrue();
                assertThat(count("SELECT count(*) FROM ad_sync_log WHERE status <> 'RUNNING'"))
                        .as("the start did not wait for the sync").isZero();

                await(() -> count("SELECT count(*) FROM ad_sync_log WHERE status = 'FAILED'") == 1);

                assertThat(jdbc.queryForObject("SELECT error_code FROM ad_sync_log", String.class))
                        .isIn("TIMEOUT", "NETWORK");
                assertThat(app.isActive()).as("still running after the failed sync").isTrue();
            }
        }
    }

    @Test
    void startupDryRunWritesTheReportAndNothingToTheDatabase() throws Exception {
        Path report = dir.resolve("reports/ad-sync-report.json");

        try (ConfigurableApplicationContext app = start("--AD_SYNC_MODE=mock", "--AD_SYNC_ENABLED=true",
                "--AD_SYNC_CRON=-", "--AD_SYNC_STARTUP_DRY_RUN=true", "--AD_SYNC_ON_STARTUP=true",
                "--AD_SYNC_REPORT_PATH=" + report)) {
            await(() -> Files.exists(report));
        }

        String text = Files.readString(report);
        JsonNode json = new ObjectMapper().readTree(text);
        assertThat(json.get("status").asText()).isEqualTo("OK");
        assertThat(json.get("trigger").asText()).isEqualTo("STARTUP");
        assertThat(json.get("fetchedCount").asInt()).isEqualTo(41);
        assertThat(json.get("skippedCount").asInt()).isEqualTo(2);
        assertThat(json.get("skippedByReason").get("COUNTRY_NOT_ALLOWED").asInt()).isEqualTo(1);
        assertThat(json.get("byCountry").get("KZ").asInt()).isPositive();
        assertThat(text).as("counts only: no e-mails, logins or names").doesNotContain("@", "kz.example", "aamber",
                "Alfa", "Amber", "CN=");
        assertThat(count("SELECT count(*) FROM employees")).isZero();
        assertThat(count("SELECT count(*) FROM employee_overrides")).isZero();
        assertThat(count("SELECT count(*) FROM ad_sync_log")).isZero();
    }

    /** A second application on the same database; command-line arguments win over every other setting */
    private ConfigurableApplicationContext start(String... adSettings) {
        List<String> args = new ArrayList<>();
        args.add("--spring.profiles.active=local");
        args.add("--spring.main.banner-mode=off");
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "app.data.excel-path", "app.data.photos-path", "app.data.watch-interval-ms")) {
            String value = environment.getProperty(key);
            if (value != null) {
                args.add("--" + key + "=" + value);
            }
        }
        args.addAll(List.of(adSettings));
        return new SpringApplicationBuilder(CsOrgchartApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached within 30 s");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
