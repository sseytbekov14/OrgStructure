package cs_orgchart.ad.health;

import cs_orgchart.TestAccounts;
import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the AD sync on and stale, /actuator/health stays 200 and UP (CI checks it); an admin sees STALE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdSyncHealthEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthCircuitBreaker breaker;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("auth.password.enabled", () -> "true");
        registry.add("auth.password.users", TestAccounts::users);
        registry.add("auth.roles.system-admin-emails", () -> TestAccounts.ADMIN);
        registry.add("AD_SYNC_MODE", () -> "mock");
        registry.add("AD_SYNC_ENABLED", () -> "true");
        registry.add("AD_SYNC_CRON", () -> "-");
    }

    @AfterEach
    void lift() {
        breaker.recordSuccess();
    }

    @Test
    void staleSyncKeepsHealth200AndUp() throws Exception {
        breaker.recordFailure(AdErrorCode.AUTH, "invalid credentials");

        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.adSync.status").value("STALE"))
                .andExpect(jsonPath("$.components.adSync.details.suspended").exists());
    }

    private MockHttpSession admin() throws Exception {
        Cookie token = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return (MockHttpSession) mvc.perform(post("/login").cookie(token).param("_csrf", token.getValue())
                        .param("username", TestAccounts.ADMIN).param("password", TestAccounts.PASSWORD))
                .andReturn().getRequest().getSession();
    }
}
