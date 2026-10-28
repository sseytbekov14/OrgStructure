package cs_orgchart.db;

import cs_orgchart.ad.sync.AdSyncLog;
import cs_orgchart.ad.sync.AdSyncLogRepository;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The Flyway migrations on a real PostgreSQL (the "local" profile, as in CI): H2 knows neither partial nor
 * functional indexes. The context starting at all means {@code ddl-auto: validate} accepted the schema.
 * Works on an empty database (V1 runs) and on one made before Flyway (baselined at 1); test rows are rolled back
 * or deleted.
 */
@SpringBootTest
@ActiveProfiles("local")
class FlywayMigrationsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private EmployeeOverrideRepository overrides;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AdSyncLogRepository syncLog;

    @Test
    void allMigrationsAreApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()",
                String.class);
        assertThat(tables).contains(
                "employee_interactions", "employee_likes", "page_visits", "search_logs",
                "employees", "ad_sync_log", "department_aliases", "department_general_nodes",
                "department_function_map", "employee_overrides");
    }

    @Test
    void indexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()", String.class);
        assertThat(indexes).contains(
                "ux_employees_dn_lower", "ux_employees_upn_lower", "ix_employees_email_lower",
                "ix_employees_manager_id", "ix_employees_pm_id", "ix_employees_dept", "ix_employees_country",
                "ix_employees_enabled", "ux_dfm", "ux_overrides_employee_cs", "ux_overrides_cs_head_rank",
                "ix_overrides_cs");
    }

    // --- employee_overrides: one head per rank and function -------------------------------------------------

    @Test
    void headRankIndexIsUniqueAndPartial() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'ux_overrides_cs_head_rank'",
                String.class);
        assertThat(definition)
                .startsWith("CREATE UNIQUE INDEX")
                .contains("(cs, head_rank)")
                .endsWith("WHERE (head_rank IS NOT NULL)");
    }

    @Test
    @Transactional
    void sameRankInAnotherFunctionAndAnyNumberOfNonHeadsAreAllowed() {
        long a = insertEmployee("a");
        long b = insertEmployee("b");
        long c = insertEmployee("c");
        insertOverride(a, "ITS", 1);
        insertOverride(b, "ITS", 2);
        insertOverride(c, "QRM", 1);
        insertOverride(c, "ITS", null);
        insertOverride(a, "QRM", null);
        insertOverride(b, "QRM", null);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM employee_overrides WHERE employee_id IN (?, ?, ?)", Long.class, a, b, c))
                .isEqualTo(6);
    }

    @Test
    @Transactional
    void twoHeadsWithTheSameRankInOneFunctionAreRejected() {
        long a = insertEmployee("a");
        long b = insertEmployee("b");
        insertOverride(a, "ITS", 1);

        assertThatThrownBy(() -> insertOverride(b, "ITS", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_overrides_cs_head_rank");
    }

    @Test
    @Transactional
    void headRankBelowOneIsRejected() {
        long a = insertEmployee("a");

        assertThatThrownBy(() -> insertOverride(a, "ITS", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_head_rank_positive");
    }

    @Test
    @Transactional
    void secondRowForTheSamePersonAndFunctionIsRejected() {
        long a = insertEmployee("a");
        insertOverride(a, "ITS", null);

        assertThatThrownBy(() -> insertOverride(a, "ITS", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_overrides_employee_cs");
    }

    @Test
    void staleOverrideEditFailsWithOptimisticLock() {
        Employee employee = employees.save(employee("lock").build());
        try {
            EmployeeOverride saved = overrides.save(EmployeeOverride.builder()
                    .employeeId(employee.getId()).cs("ITS").areaOfDuties("first").build());
            assertThat(saved.getVersion()).isZero();
            assertThat(saved.getUpdatedAt()).isNotNull();

            EmployeeOverride mine = overrides.findById(saved.getId()).orElseThrow();
            EmployeeOverride theirs = overrides.findById(saved.getId()).orElseThrow();
            theirs.setAreaOfDuties("theirs");
            assertThat(overrides.save(theirs).getVersion()).isEqualTo(1);

            mine.setAreaOfDuties("mine");
            assertThatThrownBy(() -> overrides.save(mine))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(overrides.findByEmployeeIdAndCs(employee.getId(), "ITS").orElseThrow().getAreaOfDuties())
                    .isEqualTo("theirs");
        } finally {
            employees.deleteById(employee.getId()); // its overrides go with it (ON DELETE CASCADE)
        }
        assertThat(overrides.findByEmployeeIdAndCs(employee.getId(), "ITS")).isEmpty();
    }

    // --- employees -----------------------------------------------------------------------------------------

    @Test
    @Transactional
    void employeeIsStoredAndReadBackWithAllFields() {
        Instant changed = Instant.parse("2026-10-23T08:15:30Z");
        Employee saved = employees.save(employee("full")
                .email("full@example.test").employeeId("E-1").firstName("Test").lastName("Full")
                .jobTitle("Senior Manager, UZ").company("Example").department("CS/ITS/ITS/ITS General")
                .deptLevel1("Central Services").deptLevel2("ITS").deptLevel3("ITS General")
                .managerDn("CN=boss,OU=Almaty,OU=Users,OU=KZ,OU=Countries,DC=example,DC=test")
                .pmUpn("boss@example.test").countryCode("UZ").office("Office").city("City")
                .phone("+000").mobile("+001").external(true).adWhenChanged(changed)
                .build());
        Employee boss = employees.save(employee("boss").build());
        saved.setManagerId(boss.getId());
        saved.setPmId(boss.getId());
        entityManager.flush();
        entityManager.clear();

        Employee read = employees.findByAdGuid(saved.getAdGuid()).orElseThrow();
        assertThat(read).usingRecursiveComparison()
                .ignoringFields("lastSyncedAt", "createdAt", "updatedAt")
                .isEqualTo(saved);
        assertThat(read.getAdWhenChanged()).isEqualTo(changed);
        assertThat(read.getCreatedAt()).isCloseTo(saved.getCreatedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(read.isEnabled()).isTrue();
        assertThat(read.isExternal()).isTrue();
    }

    @Test
    @Transactional
    void upnIsUniqueIgnoringCase() {
        insertEmployee("case");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO employees (ad_guid, dn, login, upn, display_name, country_code)"
                        + " VALUES (?, ?, ?, ?, ?, 'KZ')",
                UUID.randomUUID(), "CN=other", "other", "CASE@MIGRATION.TEST", "Other"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_employees_upn_lower");
    }

    @Test
    @Transactional
    void dnIsUniqueIgnoringCase() {
        insertEmployee("dn");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO employees (ad_guid, dn, login, upn, display_name, country_code)"
                        + " VALUES (?, ?, ?, ?, ?, 'KZ')",
                UUID.randomUUID(), "cn=DN,ou=migration,dc=test", "other", "other@migration.test", "Other"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_employees_dn_lower");
    }

    // --- V6 (spec v3 §4.4, §6.1, §6.2) ---------------------------------------------------------------------

    @Test
    void syncLogHasTheSkippedCount() {
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = 'ad_sync_log'"
                        + " AND column_name = 'skipped_count'", String.class))
                .isEqualTo("integer");
    }

    @Test
    @Transactional
    void syncLogStoresTheSkippedCount() {
        AdSyncLog saved = syncLog.save(AdSyncLog.builder().status("SUCCESS").triggerType("MANUAL")
                .fetchedCount(217).skippedCount(2).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(syncLog.findById(saved.getId())).hasValueSatisfying(log -> {
            assertThat(log.getFetchedCount()).isEqualTo(217);
            assertThat(log.getSkippedCount()).isEqualTo(2);
        });
    }

    @Test
    @Transactional
    void syncedEmployeeWithoutCountryIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO employees (ad_guid, dn, login, upn, display_name, is_external)"
                        + " VALUES (?, ?, ?, ?, ?, FALSE)",
                UUID.randomUUID(), "CN=nocountry,OU=Migration,DC=test", "nocountry", "nocountry@migration.test",
                "Migration, nocountry"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_employees_country");
    }

    @Test
    @Transactional
    void externalWithoutCountryIsAccepted() {
        Employee partner = employees.save(employee("partner").countryCode(null).external(true).build());
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT country_code IS NULL AND is_external FROM employees WHERE id = ?",
                Boolean.class, partner.getId())).isTrue();
    }

    // --- seeds (spec §5.3, §5.5) ---------------------------------------------------------------------------

    @Test
    void departmentAliasesAreSeeded() {
        Map<String, String> aliases = keyValue("SELECT alias, canonical FROM department_aliases");
        assertThat(aliases).containsAllEntriesOf(Map.ofEntries(
                Map.entry("cs", "Central Services"),
                Map.entry("central services", "Central Services"),
                Map.entry("f&o", "Facilities & Operations"),
                Map.entry("facilities & operations", "Facilities & Operations"),
                Map.entry("ogc", "Office of General Counsel"),
                Map.entry("office of general counsel", "Office of General Counsel"),
                Map.entry("office general counsel", "Office of General Counsel"),
                Map.entry("hr admin", "HR Administration"),
                Map.entry("hr administration", "HR Administration"),
                Map.entry("graduate", "Graduate Recruiting"),
                Map.entry("graduate recruiting", "Graduate Recruiting"),
                Map.entry("tenders", "Proposals & Tenders"),
                Map.entry("proposals & tenders", "Proposals & Tenders"),
                Map.entry("accounting general", "Accounting General"),
                Map.entry("brand & hospitality", "Brand & Hospitality"),
                Map.entry("compensation & benefits", "Compensation & Benefits"),
                Map.entry("designers markets", "Designers Markets"),
                Map.entry("it operations", "IT Operations"),
                Map.entry("it pmo", "IT PMO"),
                Map.entry("l&d", "L&D"),
                Map.entry("talent acquisition", "Talent Acquisition")));
        assertThat(aliases.keySet()).allSatisfy(alias -> assertThat(alias).isLowerCase());
    }

    @Test
    void departmentGeneralNodesAreSeeded() {
        assertThat(keyValue("SELECT function_name, general_name FROM department_general_nodes"))
                .containsAllEntriesOf(Map.of(
                        "ITS", "ITS General",
                        "QRM", "QRM General",
                        "Finance", "Finance General",
                        "Markets", "Markets General",
                        "People", "People General",
                        "Facilities & Operations", "F&O General",
                        "Office of General Counsel", "OGC General",
                        "Procurement", "Procurement General",
                        "Executives", "Administration Office"));
    }

    @Test
    void departmentFunctionMapIsSeeded() {
        Map<String, String> map = keyValue(
                "SELECT dept_level2 || ' / ' || coalesce(dept_level3, '*'), cs_name FROM department_function_map WHERE visible");
        assertThat(map).containsAllEntriesOf(Map.of(
                "Facilities & Operations / Proposals & Tenders", "Tenders",
                "Facilities & Operations / *", "F&O",
                "Executives / *", "F&O",
                "Procurement / *", "F&O",
                "Office of General Counsel / *", "OGC",
                "People / *", "People",
                "Finance / *", "Finance",
                "ITS / *", "ITS",
                "Markets / *", "Markets",
                "QRM / *", "QRM"));
        assertThat(jdbc.queryForList("SELECT DISTINCT cs_name FROM department_function_map", String.class))
                .containsExactlyInAnyOrder("People", "Finance", "ITS", "Markets", "F&O", "OGC", "QRM", "Tenders");
    }

    @Test
    @Transactional
    void departmentFunctionMapIsUniqueIgnoringCase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO department_function_map (dept_level2, dept_level3, cs_name) VALUES ('people', NULL, 'X')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_dfm");
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static Employee.EmployeeBuilder employee(String name) {
        return Employee.builder()
                .adGuid(UUID.randomUUID())
                .dn("CN=" + name + ",OU=Migration,DC=test")
                .login(name)
                .upn(name + "@migration.test")
                .displayName("Migration, " + name)
                .countryCode("KZ");
    }

    private long insertEmployee(String name) {
        Employee e = employee(name).build();
        return jdbc.queryForObject(
                "INSERT INTO employees (ad_guid, dn, login, upn, display_name, country_code) VALUES (?, ?, ?, ?, ?, ?)"
                        + " RETURNING id",
                Long.class, e.getAdGuid(), e.getDn(), e.getLogin(), e.getUpn(), e.getDisplayName(), e.getCountryCode());
    }

    private void insertOverride(long employeeId, String cs, Integer headRank) {
        jdbc.update("INSERT INTO employee_overrides (employee_id, cs, head_rank) VALUES (?, ?, ?)",
                employeeId, cs, headRank);
    }

    private Map<String, String> keyValue(String sql) {
        return jdbc.query(sql, rs -> {
            Map<String, String> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
            return result;
        });
    }
}
