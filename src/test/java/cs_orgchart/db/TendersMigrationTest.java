package cs_orgchart.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15: Tenders becomes part of F&O. Runs the migrations in a schema of its own: V1-V14, rows of both functions, then
 * V15. Nothing admins made may be lost: groups (also one with a name F&O already has), memberships (also somebody in
 * both functions), the order inside groups and the FAQ; the heads of Tenders become members, F&O keeps its heads.
 */
@SpringBootTest
@ActiveProfiles("local")
class TendersMigrationTest {

    private static final String SCHEMA = "tenders_migration";
    private static final String S = SCHEMA + ".";

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private long tendersHead;
    private long inBoth;
    private long tendersMember;
    private long foHead;
    private long sameNameTenders;
    private long sameNameFo;
    private long onlyTendersGroup;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }

    private long employee(String login) {
        return jdbc.queryForObject("INSERT INTO " + S + "employees (ad_guid, dn, login, upn, display_name, enabled,"
                + " is_external, country_code) VALUES (gen_random_uuid(), ?, ?, ?, ?, TRUE, FALSE, 'KZ') RETURNING id",
                Long.class, "CN=" + login + ",DC=x", login, login + "@x.example", login);
    }

    private long group(String cs, String name, int sortOrder) {
        return jdbc.queryForObject("INSERT INTO " + S + "function_groups (cs, name, sort_order) VALUES (?, ?, ?) RETURNING id",
                Long.class, cs, name, sortOrder);
    }

    private void membership(long employee, String cs, Long groupId, Integer headRank, String duties) {
        jdbc.update("INSERT INTO " + S + "employee_overrides (employee_id, cs, group_id, head_rank, area_of_duties)"
                + " VALUES (?, ?, ?, ?, ?)", employee, cs, groupId, headRank, duties);
    }

    @BeforeEach
    void databaseOfTheEightFunctions() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        flyway("14").migrate();
        tendersHead = employee("thead");
        inBoth = employee("both");
        tendersMember = employee("tmember");
        foHead = employee("fohead");
        sameNameTenders = group("Tenders", "Bids", 10);
        sameNameFo = group("F&O", "bids", 40);
        onlyTendersGroup = group("Tenders", "Proposals & Tenders", 20);
        membership(tendersHead, "Tenders", null, 1, "Tender lead");
        membership(foHead, "F&O", null, 1, "F&O lead");
        membership(inBoth, "Tenders", sameNameTenders, null, "From Tenders");
        membership(inBoth, "F&O", null, null, null);
        membership(tendersMember, "Tenders", sameNameTenders, null, "Member");
        jdbc.update("INSERT INTO " + S + "group_member_order (cs, employee_id, group_id, position) VALUES"
                + " ('Tenders', ?, ?, 10)", tendersMember, onlyTendersGroup);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + S + sql, Long.class, args);
    }

    @Test
    void tendersIsNoLongerAFunctionAnywhere() {
        flyway("15").migrate();

        assertThat(count("department_function_map WHERE cs_name = 'Tenders'")).isZero();
        assertThat(count("employee_overrides WHERE cs = 'Tenders'")).isZero();
        assertThat(count("function_groups WHERE cs = 'Tenders'")).isZero();
        assertThat(count("group_member_order WHERE cs = 'Tenders'")).isZero();
        assertThat(count("function_faq WHERE cs = 'Tenders'")).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT cs_name FROM " + S + "department_function_map", String.class))
                .containsExactlyInAnyOrder("People", "Finance", "ITS", "Markets", "F&O", "OGC", "QRM");
    }

    @Test
    void theHeadsOfTendersBecomeMembersAndFoKeepsItsHead() {
        flyway("15").migrate();

        assertThat(jdbc.queryForObject("SELECT head_rank FROM " + S + "employee_overrides WHERE employee_id = ? AND cs = 'F&O'",
                Integer.class, tendersHead)).isNull();
        assertThat(jdbc.queryForObject("SELECT area_of_duties FROM " + S + "employee_overrides WHERE employee_id = ?",
                String.class, tendersHead)).isEqualTo("Tender lead");
        assertThat(jdbc.queryForObject("SELECT head_rank FROM " + S + "employee_overrides WHERE employee_id = ?",
                Integer.class, foHead)).isEqualTo(1);
    }

    @Test
    void aGroupWithTheSameNameTakesThePeopleTheOthersMoveAfterTheGroupsOfFo() {
        flyway("15").migrate();

        assertThat(jdbc.queryForObject("SELECT group_id FROM " + S + "employee_overrides WHERE employee_id = ?", Long.class,
                tendersMember)).isEqualTo(sameNameFo);
        assertThat(count("function_groups WHERE id = ?", sameNameTenders)).isZero();
        assertThat(jdbc.queryForObject("SELECT cs FROM " + S + "function_groups WHERE id = ?", String.class, onlyTendersGroup))
                .isEqualTo("F&O");
        assertThat(jdbc.queryForObject("SELECT sort_order FROM " + S + "function_groups WHERE id = ?", Integer.class,
                onlyTendersGroup)).isEqualTo(60);
    }

    @Test
    void somebodyInBothFunctionsKeepsOneRowAndGainsTheTexts() {
        flyway("15").migrate();

        assertThat(count("employee_overrides WHERE employee_id = ?", inBoth)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT area_of_duties FROM " + S + "employee_overrides WHERE employee_id = ?",
                String.class, inBoth)).isEqualTo("From Tenders");
    }

    @Test
    void theOrderAndTheFaqMoveToo() {
        flyway("15").migrate();

        assertThat(jdbc.queryForObject("SELECT cs FROM " + S + "group_member_order WHERE employee_id = ?", String.class,
                tendersMember)).isEqualTo("F&O");
        assertThat(jdbc.queryForList("SELECT s ->> 'title' FROM " + S + "function_faq f,"
                        + " jsonb_array_elements(f.content -> 'sections') s WHERE f.cs = 'F&O'", String.class))
                .containsExactly("Request rules", "Team mailboxes", "Tenders: Request rules", "Tenders: Team mailboxes");
        assertThat(jdbc.queryForObject("SELECT version FROM " + S + "function_faq WHERE cs = 'F&O'", Long.class)).isEqualTo(1);
    }
}
