package cs_orgchart.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.TestAccounts;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base of the MockMvc tests of the admin features on PostgreSQL (DB_* settings, profile local), db mode, with the mock
 * directory synced before each test. Four callers: anonymous, {@link #reader} (signed in, no admin),
 * {@link #editor} (admin through app_admins) and {@link #superAdmin} (SYSTEM_ADMIN_EMAILS).
 */
@SpringBootTest(properties = {"EMPLOYEE_SOURCE=db", "AD_SYNC_MODE=mock", "AD_SYNC_ENABLED=false"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
public abstract class DbModeMvcSupport {

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("auth.password.enabled", () -> "true");
        registry.add("auth.password.users", TestAccounts::usersWithEditors);
        registry.add("auth.roles.system-admin-emails", () -> TestAccounts.ADMIN);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AdSyncService sync;

    @Autowired
    protected EmployeeRepository employees;

    @Autowired
    protected AdminAccess adminAccess;

    protected final ObjectMapper json = new ObjectMapper();
    protected MockHttpSession superAdmin;
    protected MockHttpSession editor;
    protected MockHttpSession reader;
    protected Cookie token;

    @BeforeEach
    void syncMockDirectoryAndSignIn() throws Exception {
        cleanDatabase();
        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        // Stored with other casing than the sign-in name: the match ignores case
        jdbc.update("INSERT INTO app_admins (login_name, note, added_by) VALUES (?, 'test', 'test')",
                TestAccounts.EDITOR.toUpperCase());
        adminAccess.evict();
        superAdmin = signIn(TestAccounts.ADMIN);
        editor = signIn(TestAccounts.EDITOR);
        reader = signIn(TestAccounts.READER);
        token = csrf();
    }

    @AfterEach
    void cleanDatabase() {
        for (String table : tablesToClean()) {
            jdbc.update("DELETE FROM " + table);
        }
        adminAccess.evict();
    }

    /** Child tables first */
    protected String[] tablesToClean() {
        return new String[]{"app_admins", "employee_overrides", "employees", "ad_sync_log"};
    }

    protected MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, MockHttpSession session,
                                                  Object body) throws Exception {
        if (session != null) {
            request.session(session);
        }
        request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return request;
    }

    protected Cookie csrf() throws Exception {
        Cookie cookie = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    protected MockHttpSession signIn(String user) throws Exception {
        Cookie cookie = csrf();
        return (MockHttpSession) mvc.perform(post("/login").cookie(cookie).param("_csrf", cookie.getValue())
                        .param("username", user).param("password", TestAccounts.PASSWORD))
                .andReturn().getRequest().getSession();
    }

    protected long id(String login) {
        return employees.findAll().stream().filter(e -> e.getLogin().equals(login)).findFirst()
                .map(Employee::getId).orElseThrow();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
