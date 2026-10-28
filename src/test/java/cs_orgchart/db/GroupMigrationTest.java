package cs_orgchart.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U4 migration (V8): the group names of the overlay become groups of their function. Runs the migrations in a schema
 * of its own: V1-V7, legacy rows with group names, then V8-V9. No assignment may be lost, case and spaces do not make
 * two groups, country codes become COUNTRY groups, and running the data statements again changes nothing.
 */
@SpringBootTest
@ActiveProfiles("local")
class GroupMigrationTest {

    private static final String SCHEMA = "u4_group_migration";

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }

    @BeforeEach
    void legacyDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        flyway("7").migrate();
        for (int i = 1; i <= 6; i++) {
            jdbc.update("INSERT INTO " + SCHEMA + ".employees (ad_guid, dn, login, upn, display_name, department,"
                    + " enabled, is_external, country_code) VALUES (gen_random_uuid(), ?, ?, ?, ?, 'CS/ITS', TRUE,"
                    + " FALSE, 'KZ')", "CN=p" + i + ",DC=x", "p" + i, "p" + i + "@x.example", "Person " + i);
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM " + SCHEMA + ".employees ORDER BY id", Long.class);
        insertRow(ids.get(0), "ITS", "Service Desk");
        insertRow(ids.get(1), "ITS", "  service desk ");
        insertRow(ids.get(2), "ITS", "uz");
        insertRow(ids.get(3), "QRM", "Service Desk");
        insertRow(ids.get(4), "ITS", null);
        insertRow(ids.get(5), "ITS", "   ");
    }

    private void insertRow(long employeeId, String cs, String groupName) {
        jdbc.update("INSERT INTO " + SCHEMA + ".employee_overrides (employee_id, cs, group_name) VALUES (?, ?, ?)",
                employeeId, cs, groupName);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private List<Map<String, Object>> assignments() {
        return jdbc.queryForList("SELECT o.employee_id, o.cs, o.group_name, o.group_id, g.name AS group, g.kind"
                + " FROM " + SCHEMA + ".employee_overrides o LEFT JOIN " + SCHEMA + ".function_groups g"
                + " ON g.id = o.group_id ORDER BY o.employee_id");
    }

    @Test
    void everyGroupNameGetsItsGroupAndASecondRunChangesNothing() throws IOException {
        flyway("latest").migrate();

        List<Map<String, Object>> rows = assignments();
        for (Map<String, Object> row : rows) {
            String legacy = (String) row.get("group_name");
            if (legacy == null || legacy.isBlank()) {
                assertThat(row.get("group_id")).as("no group for %s", row).isNull();
            } else {
                assertThat(row.get("group_id")).as("assignment kept for %s", row).isNotNull();
                assertThat(((String) row.get("group")).toLowerCase()).isEqualTo(legacy.trim().toLowerCase());
            }
        }
        assertThat(jdbc.queryForList("SELECT cs || ':' || name || ':' || kind FROM " + SCHEMA + ".function_groups"
                + " ORDER BY cs, name", String.class))
                .containsExactly("ITS:Service Desk:TEAM", "ITS:uz:COUNTRY", "QRM:Service Desk:TEAM");
        assertThat(rows.get(0).get("group_id")).as("one group for different case and spaces")
                .isEqualTo(rows.get(1).get("group_id"));
        assertThat(rows.get(0).get("group_id")).as("one group per function").isNotEqualTo(rows.get(3).get("group_id"));

        List<Map<String, Object>> groupsBefore = jdbc.queryForList("SELECT * FROM " + SCHEMA + ".function_groups ORDER BY id");
        List<Map<String, Object>> rowsBefore = assignments();
        runDataStatementsOfV8Again();
        assertThat(jdbc.queryForList("SELECT * FROM " + SCHEMA + ".function_groups ORDER BY id")).isEqualTo(groupsBefore);
        assertThat(assignments()).isEqualTo(rowsBefore);
    }

    @Test
    void theNewTablesAndColumnsExist() {
        flyway("latest").migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'employee_overrides' AND column_name IN ('group_id', 'group_name')", Long.class,
                SCHEMA)).as("group_name is kept for this release").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = ?"
                + " AND table_name IN ('function_groups', 'employee_visibility')", Long.class, SCHEMA)).isEqualTo(2);
    }

    /** The INSERT ... ON CONFLICT DO NOTHING and the UPDATE ... WHERE group_id IS NULL of V8, once more */
    private void runDataStatementsOfV8Again() throws IOException {
        String sql = new ClassPathResource("db/migration/V8__function_groups.sql").getContentAsString(StandardCharsets.UTF_8);
        String data = sql.substring(sql.indexOf("INSERT INTO function_groups"));
        jdbc.execute("SET search_path TO " + SCHEMA);
        try {
            for (String statement : data.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement);
                }
            }
        } finally {
            jdbc.execute("SET search_path TO public");
        }
    }
}
