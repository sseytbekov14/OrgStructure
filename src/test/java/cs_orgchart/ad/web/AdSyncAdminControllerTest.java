package cs_orgchart.ad.web;

import cs_orgchart.TestAccounts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/admin/ad-sync and GET /api/admin/ad-sync/log: SYSTEM_ADMIN only (the /api/admin/** rule of
 * SecurityConfig and @PreAuthorize), CSRF required for the POST. The token is taken as the browser does it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdSyncAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("auth.password.enabled", () -> "true");
        registry.add("auth.password.users", TestAccounts::users);
        registry.add("auth.roles.system-admin-emails", () -> TestAccounts.ADMIN);
        registry.add("AD_SYNC_MODE", () -> "mock");
        registry.add("AD_SYNC_ENABLED", () -> "false");
    }

    private Cookie csrf() throws Exception {
        Cookie token = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return token;
    }

    private MockHttpSession signIn(String user) throws Exception {
        Cookie token = csrf();
        return (MockHttpSession) mvc.perform(post("/login").cookie(token).param("_csrf", token.getValue())
                        .param("username", user).param("password", TestAccounts.PASSWORD))
                .andReturn().getRequest().getSession();
    }

    @Test
    void anonymousIs401() throws Exception {
        Cookie token = csrf();
        mvc.perform(get("/api/admin/ad-sync/log")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/ad-sync").param("dryRun", "true").cookie(token)
                .header("X-XSRF-TOKEN", token.getValue())).andExpect(status().isUnauthorized());
    }

    @Test
    void userIs403() throws Exception {
        MockHttpSession reader = signIn(TestAccounts.READER);
        Cookie token = csrf();
        mvc.perform(get("/api/admin/ad-sync/log").session(reader)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/ad-sync").param("dryRun", "true").session(reader).cookie(token)
                .header("X-XSRF-TOKEN", token.getValue())).andExpect(status().isForbidden());
    }

    @Test
    void adminReadsTheLogAndRunsADryRun() throws Exception {
        MockHttpSession admin = signIn(TestAccounts.ADMIN);
        Cookie token = csrf();

        mvc.perform(get("/api/admin/ad-sync/log").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(post("/api/admin/ad-sync").param("dryRun", "true").session(admin).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.fetchedCount").value(41))
                .andExpect(jsonPath("$.skippedCount").value(2))
                .andExpect(jsonPath("$.skippedByReason.COUNTRY_NOT_ALLOWED").value(1));
    }

    @Test
    void postWithoutTheCsrfTokenIs403EvenForTheAdmin() throws Exception {
        MockHttpSession admin = signIn(TestAccounts.ADMIN);

        mvc.perform(post("/api/admin/ad-sync").param("dryRun", "true").session(admin))
                .andExpect(status().isForbidden());
    }

    @Test
    void realSyncNeedsAdSyncEnabled() throws Exception {
        MockHttpSession admin = signIn(TestAccounts.ADMIN);
        Cookie token = csrf();

        mvc.perform(post("/api/admin/ad-sync").session(admin).cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void healthStays200AndShowsTheSyncAsDisabled() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health").session(signIn(TestAccounts.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.adSync.status").value("DISABLED"));
    }
}
