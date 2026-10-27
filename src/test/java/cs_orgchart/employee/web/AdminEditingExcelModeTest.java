package cs_orgchart.employee.web;

import cs_orgchart.TestAccounts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EMPLOYEE_SOURCE=excel (the default): admin editing is off. Every overlay and heads endpoint answers 409 (also for
 * SYSTEM_ADMIN) and /api/me/roles tells the pages to hide the edit controls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminEditingExcelModeTest {

    private static final String DISABLED = "admin editing requires EMPLOYEE_SOURCE=db";

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("auth.password.enabled", () -> "true");
        registry.add("auth.password.users", TestAccounts::users);
        registry.add("auth.roles.system-admin-emails", () -> TestAccounts.ADMIN);
    }

    @Test
    void everyEditingEndpointIs409ForTheAdmin() throws Exception {
        MockHttpSession admin = signIn(TestAccounts.ADMIN);
        Cookie token = csrf();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/admin/overrides").param("cs", "ITS"),
                put("/api/admin/overrides/1").param("cs", "ITS").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaOfDuties\":\"x\"}"),
                delete("/api/admin/overrides/1").param("cs", "ITS"),
                get("/api/admin/functions/ITS/heads"),
                put("/api/admin/functions/ITS/heads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeIds\":[],\"expectedHeads\":[]}"))) {
            mvc.perform(request.session(admin).cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EDITING_DISABLED"))
                    .andExpect(jsonPath("$.message").value(DISABLED));
        }
    }

    @Test
    void rolesTellThePagesThatEditingIsOff() throws Exception {
        mvc.perform(get("/api/me/roles").session(signIn(TestAccounts.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(hasItems("USER", "SYSTEM_ADMIN")))
                .andExpect(jsonPath("$.editingEnabled").value(false));
    }

    @Test
    void userStillGets403BeforeTheModeIsLookedAt() throws Exception {
        mvc.perform(get("/api/admin/overrides").param("cs", "ITS").session(signIn(TestAccounts.READER)))
                .andExpect(status().isForbidden());
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
}
