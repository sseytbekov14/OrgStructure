package cs_orgchart.ad.sync;

import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The sync tests on PostgreSQL (the "local" profile, as in CI; advisory locks and the migrations need it): mock mode
 * with a {@link FakeDirectory}, AD_SYNC_ENABLED=false so that no schedule or startup task runs by itself. Every test
 * starts and ends with empty employees, overlay and sync log.
 */
@SpringBootTest(properties = {"AD_SYNC_MODE=mock", "AD_SYNC_ENABLED=false"})
@ActiveProfiles("local")
@Import(AdSyncTestSupport.FakeDirectoryConfig.class)
abstract class AdSyncTestSupport {

    @TestConfiguration
    static class FakeDirectoryConfig {
        @Bean
        @Primary
        FakeDirectory fakeDirectory() {
            return new FakeDirectory();
        }
    }

    @Autowired
    protected AdSyncService service;

    @Autowired
    protected FakeDirectory directory;

    @Autowired
    protected AuthCircuitBreaker breaker;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected EmployeeRepository employees;

    @Autowired
    protected EmployeeOverrideRepository overrides;

    @Autowired
    protected AdSyncLogRepository syncLog;

    @BeforeEach
    void startClean() {
        directory.reset();
        breaker.recordSuccess();
        clean();
    }

    @AfterEach
    void endClean() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM employee_overrides");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM ad_sync_log");
    }

    protected Employee employee(String login) {
        return employees.findAll().stream().filter(e -> e.getLogin().equals(login)).findFirst().orElseThrow();
    }

    protected AdSyncLog logRow(Long id) {
        return syncLog.findById(id).orElseThrow();
    }

    protected long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
