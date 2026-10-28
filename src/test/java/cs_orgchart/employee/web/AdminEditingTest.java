package cs_orgchart.employee.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.TestAccounts;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.override.OverrideAdminService;
import cs_orgchart.service.OrgStreamService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin editing of the overlay and the heads (spec v3 §10.2) on PostgreSQL, db mode, with the mock directory synced
 * before each test (employees and the seeded mock overlay). Sign-in with the password form; SYSTEM_ADMIN through
 * SYSTEM_ADMIN_EMAILS.
 */
@SpringBootTest(properties = {"EMPLOYEE_SOURCE=db", "AD_SYNC_MODE=mock", "AD_SYNC_ENABLED=false"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AdminEditingTest {

    private static final String XSS = "<img src=x onerror=alert(1)>";

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("auth.password.enabled", () -> "true");
        registry.add("auth.password.users", TestAccounts::users);
        registry.add("auth.roles.system-admin-emails", () -> TestAccounts.ADMIN);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdSyncService sync;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private EmployeeOverrideRepository overlay;

    @Autowired
    private EmployeeDirectory directory;

    @MockitoSpyBean
    private OrgStreamService orgStream;

    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession admin;
    private MockHttpSession reader;
    private Cookie token;

    @BeforeEach
    void syncMockDirectory() throws Exception {
        clean();
        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        admin = signIn(TestAccounts.ADMIN);
        reader = signIn(TestAccounts.READER);
        token = csrf();
        clearInvocations(orgStream);
    }

    @AfterEach
    void clean() {
        reset(orgStream);
        jdbc.update("DELETE FROM employee_overrides");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM ad_sync_log");
    }

    // --- access --------------------------------------------------------------------------------------------------

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/manage/functions/ITS/heads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/roles")).andExpect(status().isUnauthorized());
    }

    @Test
    void userIs403EverywhereAndSeesNoAdminRole() throws Exception {
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(reader)).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), reader,
                Map.of("areaOfDuties", "x"))).andExpect(status().isForbidden());
        mvc.perform(write(delete("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), reader, null))
                .andExpect(status().isForbidden());
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), reader, heads(List.of(), List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me/roles").session(reader))
                .andExpect(jsonPath("$.roles").value(contains("USER")))
                .andExpect(jsonPath("$.editingEnabled").value(true));
    }

    @Test
    void adminGetsTheMembersAndTheRoles() throws Exception {
        mvc.perform(get("/api/me/roles").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(hasItems("USER", "SYSTEM_ADMIN")))
                .andExpect(jsonPath("$.editingEnabled").value(true));
        JsonNode members = body(mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(members).isNotEmpty();
        JsonNode chief = find(members, "Kilo, Khaki");
        assertThat(chief.get("override").get("headRank").asInt()).isEqualTo(1);
        assertThat(chief.get("group").asText()).isEqualTo("HEAD");
        assertThat(body(mvc.perform(get("/api/manage/overrides").param("cs", "ITS").param("q", "lemon")
                .session(admin)).andReturn())).hasSize(1);
    }

    @Test
    void writeWithoutTheCsrfTokenIs403() throws Exception {
        mvc.perform(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // --- texts ---------------------------------------------------------------------------------------------------

    @Test
    void adminUpdatesTheTextsWithTheVersionAndTheDirectoryShowsThemAfterTheCommit() throws Exception {
        EmployeeOverride before = row("mmint", "ITS");
        AtomicReference<String> committedAtSse = new AtomicReference<>();
        doAnswer(call -> {
            committedAtSse.set(committedAreaOfDuties(before.getId()));
            return call.callRealMethod();
        }).when(orgStream).publishOrgUpdated(anyString());

        MvcResult result = mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin,
                        Map.of("groupName", "  Service Desk  ", "areaOfDuties", "Laptops and accounts",
                                "jobDescription", " ", "version", before.getVersion())))
                .andExpect(status().isOk()).andReturn();

        JsonNode saved = body(result);
        assertThat(saved.get("groupName").asText()).isEqualTo("Service Desk");
        assertThat(saved.get("areaOfDuties").asText()).isEqualTo("Laptops and accounts");
        assertThat(saved.get("jobDescription").isNull()).as("empty -> null").isTrue();
        assertThat(saved.get("version").asLong()).isEqualTo(before.getVersion() + 1);
        assertThat(saved.get("updatedBy").asText()).isEqualTo(TestAccounts.ADMIN);
        verify(orgStream).publishOrgUpdated("employee-directory");
        assertThat(committedAtSse.get()).as("org-updated only after the commit").isEqualTo("Laptops and accounts");
        assertThat(dto("Mike, Mint", "ITS").getAreaOfDuties()).isEqualTo("Laptops and accounts");
    }

    @Test
    void staleVersionIs409AndChangesNothing() throws Exception {
        EmployeeOverride before = row("mmint", "ITS");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin,
                Map.of("areaOfDuties", "first", "version", before.getVersion()))).andExpect(status().isOk());

        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin,
                        Map.of("areaOfDuties", "second", "version", before.getVersion())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGED"));

        assertThat(row("mmint", "ITS").getAreaOfDuties()).isEqualTo("first");
    }

    @Test
    void newRowAddsThePersonToAnotherFunction() throws Exception {
        mvc.perform(write(put("/api/manage/overrides/" + id("nnavy")).param("cs", "QRM"), admin,
                        Map.of("groupName", "Project reviews")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));

        assertThat(dto("November, Navy", "QRM").getGroup()).isEqualTo("Project reviews");
        assertThat(directory.getAllEmployees()).filteredOn(e -> e.getName().equals("November, Navy"))
                .extracting(cs_orgchart.model.Employee::getCs).containsExactly("QRM");
    }

    @Test
    void htmlAndOverlongTextsAreRefusedAndNothingIsStored() throws Exception {
        long version = row("mmint", "ITS").getVersion();
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("areaOfDuties", XSS, "version", version),
                Map.<String, Object>of("jobDescription", "x".repeat(501), "version", version),
                Map.<String, Object>of("groupName", "g".repeat(257), "version", version),
                Map.<String, Object>of("groupName", "Team <b>", "version", version))) {
            mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin, bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(row("mmint", "ITS").getVersion()).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_overrides WHERE area_of_duties LIKE '%<%'"
                + " OR job_description LIKE '%<%' OR group_name LIKE '%<%'", Long.class)).isZero();
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin,
                Map.of("jobDescription", "x".repeat(500), "version", version))).andExpect(status().isOk());
    }

    @Test
    void unknownFunctionDisabledExternalOrMissingEmployeeAreRefused() throws Exception {
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "Sales"), admin,
                        Map.of("areaOfDuties", "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FUNCTION"));
        mvc.perform(write(put("/api/manage/overrides/" + id("rrust")).param("cs", "ITS"), admin,
                        Map.of("areaOfDuties", "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPLOYEE_EXTERNAL"));
        jdbc.update("UPDATE employees SET enabled = false WHERE login = 'nnavy'");
        mvc.perform(write(put("/api/manage/overrides/" + id("nnavy")).param("cs", "ITS"), admin,
                        Map.of("areaOfDuties", "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPLOYEE_DISABLED"));
        mvc.perform(write(put("/api/manage/overrides/999999999").param("cs", "ITS"), admin, Map.of("areaOfDuties", "x")))
                .andExpect(status().isNotFound());
        verify(orgStream, never()).publishOrgUpdated(anyString());
    }

    @Test
    void logNamesAdminEmployeeFunctionAndFieldsButNotTheTexts() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OverrideAdminService.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin,
                    Map.of("areaOfDuties", "SECRET-TEXT", "version", row("mmint", "ITS").getVersion())))
                    .andExpect(status().isOk());
        } finally {
            logger.detachAppender(logged);
        }
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).singleElement().asString()
                .contains(TestAccounts.ADMIN, String.valueOf(id("mmint")), "ITS", "areaOfDuties")
                .doesNotContain("SECRET-TEXT");
    }

    @Test
    void deleteRemovesTheRowBackToTheAdDefault() throws Exception {
        EmployeeOverride row = row("mmint", "ITS");
        mvc.perform(write(delete("/api/manage/overrides/" + id("mmint")).param("cs", "ITS")
                .param("version", String.valueOf(row.getVersion() + 5)), admin, null)).andExpect(status().isConflict());

        mvc.perform(write(delete("/api/manage/overrides/" + id("mmint")).param("cs", "ITS")
                .param("version", String.valueOf(row.getVersion())), admin, null)).andExpect(status().isNoContent());

        assertThat(overlay.findByEmployeeIdAndCs(id("mmint"), "ITS")).isEmpty();
        assertThat(dto("Mike, Mint", "ITS").getGroup()).as("default group of ITS General: none").isNull();
        mvc.perform(write(delete("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin, null))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingTheLastHeadNeedsTheConfirmation() throws Exception {
        mvc.perform(write(delete("/api/manage/overrides/" + id("qquartz")).param("cs", "QRM"), admin, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_HEAD"));
        assertThat(row("qquartz", "QRM")).isNotNull();

        mvc.perform(write(delete("/api/manage/overrides/" + id("qquartz")).param("cs", "QRM")
                .param("confirmLastHead", "true"), admin, null)).andExpect(status().isNoContent());
    }

    // --- heads ---------------------------------------------------------------------------------------------------

    @Test
    void headsAreSetInOrderAndARemovedHeadStaysAMember() throws Exception {
        List<Long> before = List.of(id("kkhaki"), id("llemon"));

        JsonNode result = body(mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin,
                heads(List.of(id("llemon"), id("kkhaki"), id("mmint")), before))).andExpect(status().isOk()).andReturn());

        assertThat(result.get("heads")).extracting(h -> h.get("employeeId").asLong())
                .containsExactly(id("llemon"), id("kkhaki"), id("mmint"));
        assertThat(rank("llemon", "ITS")).isEqualTo((short) 1);
        assertThat(rank("kkhaki", "ITS")).isEqualTo((short) 2);
        assertThat(rank("mmint", "ITS")).isEqualTo((short) 3);
        assertThat(row("mmint", "ITS").getGroupName()).as("its texts stay").isEqualTo("Service Desk");

        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin,
                heads(List.of(id("llemon")), List.of(id("llemon"), id("kkhaki"), id("mmint"))))).andExpect(status().isOk());

        assertThat(rank("kkhaki", "ITS")).as("no longer a head").isNull();
        assertThat(row("kkhaki", "ITS")).as("but still a member of ITS").isNotNull();
        assertThat(dto("Kilo, Khaki", "ITS").getGroup()).isNotEqualTo("HEAD");
        assertThat(directory.getAllEmployees().stream().filter(e -> "ITS".equals(e.getCs())).findFirst().orElseThrow()
                .getName()).isEqualTo("Lima, Lemon");
    }

    @Test
    void aPersonOfAnotherFunctionBecomesHeadThroughANewRow() throws Exception {
        mvc.perform(write(put("/api/manage/functions/QRM/heads"), admin,
                heads(List.of(id("qquartz"), id("tteal")), List.of(id("qquartz"))))).andExpect(status().isOk());

        assertThat(rank("tteal", "QRM")).isEqualTo((short) 2);
        assertThat(rank("tteal", "OGC")).as("head of OGC as before").isEqualTo((short) 1);
        assertThat(directory.getAllEmployees()).filteredOn(e -> e.getName().equals("Tango, Teal"))
                .extracting(cs_orgchart.model.Employee::getCs).containsExactlyInAnyOrder("OGC", "QRM");
    }

    @Test
    void expectedHeadsMismatchIs409AndChangesNothing() throws Exception {
        Map<Long, String> before = overlaySnapshot();

        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin,
                        heads(List.of(id("nnavy")), List.of(id("llemon")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEADS_CHANGED"));

        assertThat(overlaySnapshot()).isEqualTo(before);
        verify(orgStream, never()).publishOrgUpdated(anyString());
    }

    @Test
    void removingAllHeadsNeedsTheConfirmation() throws Exception {
        List<Long> current = List.of(id("kkhaki"), id("llemon"));
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin, heads(List.of(), current)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_HEAD"));

        Map<String, Object> confirmed = new HashMap<>(heads(List.of(), current));
        confirmed.put("confirmRemoveAllHeads", true);
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin, confirmed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.heads").isEmpty());
        assertThat(row("kkhaki", "ITS")).isNotNull();
    }

    @Test
    void headsMustBeEnabledCentralServicesPeopleListedOnceInAKnownFunction() throws Exception {
        List<Long> current = List.of(id("kkhaki"), id("llemon"));
        jdbc.update("UPDATE employees SET enabled = false WHERE login = 'nnavy'");
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin, heads(List.of(id("nnavy")), current)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPLOYEE_DISABLED"));
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin, heads(List.of(id("rrust")), current)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPLOYEE_EXTERNAL"));
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin,
                heads(List.of(id("mmint"), id("mmint")), current))).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/manage/functions/Sales/heads"), admin, heads(List.of(), List.of())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FUNCTION"));
        assertThat(rank("kkhaki", "ITS")).isEqualTo((short) 1);
    }

    @Test
    void headDisabledInAdIsHiddenButKeptAfterTheNewHeads() throws Exception {
        jdbc.update("UPDATE employees SET enabled = false WHERE login = 'kkhaki'");
        JsonNode state = body(mvc.perform(get("/api/manage/functions/ITS/heads").session(admin)).andReturn());
        assertThat(state.get("heads")).extracting(h -> h.get("employeeId").asLong()).containsExactly(id("llemon"));
        assertThat(state.get("hiddenHeads").asInt()).isEqualTo(1);

        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin,
                heads(List.of(id("mmint"), id("llemon")), List.of(id("llemon"))))).andExpect(status().isOk());

        assertThat(rank("mmint", "ITS")).isEqualTo((short) 1);
        assertThat(rank("llemon", "ITS")).isEqualTo((short) 2);
        assertThat(rank("kkhaki", "ITS")).as("kept, comes back when re-enabled").isEqualTo((short) 3);
    }

    @Test
    void aSyncChangesNeitherTheHeadsNorTheEditedTexts() throws Exception {
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), admin,
                Map.of("areaOfDuties", "Edited", "version", row("mmint", "ITS").getVersion()))).andExpect(status().isOk());
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), admin,
                heads(List.of(id("mmint"), id("kkhaki")), List.of(id("kkhaki"), id("llemon"))))).andExpect(status().isOk());
        Map<Long, String> before = overlaySnapshot();
        jdbc.update("UPDATE employees SET job_title = 'Changed in the database' WHERE login = 'mmint'");

        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);

        assertThat(overlaySnapshot()).isEqualTo(before);
        assertThat(dto("Mike, Mint", "ITS").getGroup()).isEqualTo("HEAD");
        assertThat(dto("Mike, Mint", "ITS").getAreaOfDuties()).isEqualTo("Edited");
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, MockHttpSession session,
                                                Object body) throws Exception {
        request.session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return request;
    }

    private static Map<String, Object> heads(List<Long> employeeIds, List<Long> expected) {
        return Map.of("employeeIds", employeeIds, "expectedHeads", expected);
    }

    private Cookie csrf() throws Exception {
        Cookie cookie = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MockHttpSession signIn(String user) throws Exception {
        Cookie cookie = csrf();
        return (MockHttpSession) mvc.perform(post("/login").cookie(cookie).param("_csrf", cookie.getValue())
                        .param("username", user).param("password", TestAccounts.PASSWORD))
                .andReturn().getRequest().getSession();
    }

    private long id(String login) {
        return employees.findAll().stream().filter(e -> e.getLogin().equals(login)).findFirst()
                .map(Employee::getId).orElseThrow();
    }

    private EmployeeOverride row(String login, String cs) {
        return overlay.findByEmployeeIdAndCs(id(login), cs).orElse(null);
    }

    private Short rank(String login, String cs) {
        EmployeeOverride row = row(login, cs);
        return row == null ? null : row.getHeadRank();
    }

    private cs_orgchart.model.Employee dto(String name, String cs) {
        return directory.getAllEmployees().stream().filter(e -> e.getName().equals(name) && cs.equals(e.getCs()))
                .findFirst().orElseThrow();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode members, String name) {
        for (JsonNode member : members) {
            if (name.equals(member.get("name").asText())) {
                return member;
            }
        }
        throw new AssertionError(name + " not listed");
    }

    /** What another connection sees: committed data only */
    private String committedAreaOfDuties(long overrideId) throws Exception {
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement();
             ResultSet rs = statement.executeQuery("SELECT area_of_duties FROM employee_overrides WHERE id = "
                     + overrideId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private Map<Long, String> overlaySnapshot() {
        Map<Long, String> rows = new HashMap<>();
        jdbc.query("SELECT * FROM employee_overrides ORDER BY id", (ResultSet rs) -> {
            List<Object> values = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                values.add(rs.getObject(i));
            }
            rows.put(rs.getLong("id"), values.toString());
        });
        return rows;
    }
}
