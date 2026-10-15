package cs_orgchart.config;

import cs_orgchart.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The password form (emergency sign-in): accounts of AUTH_PASSWORD_USERS, roles of the address lists. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordFormTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("auth.password.users", TestAccounts::users);
        registry.add("auth.roles.system-admin-emails", () -> TestAccounts.ADMIN);
    }

    private MvcResult signIn(String username, String password) throws Exception {
        return mockMvc.perform(post("/login").with(csrf())
                .param("username", username).param("password", password)).andReturn();
    }

    private static SecurityContext context(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        return session == null ? null : (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }

    @Test
    void aPage_withoutASession_goesToTheForm() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void theRightPassword_signsIn_withTheRolesOfTheLists() throws Exception {
        MvcResult admin = signIn(TestAccounts.ADMIN, TestAccounts.PASSWORD);
        assertThat(admin.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(context(admin).getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_SYSTEM_ADMIN");

        MvcResult reader = signIn(TestAccounts.READER, TestAccounts.PASSWORD);
        assertThat(context(reader).getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_USER");
        mockMvc.perform(get("/api/functions").session((MockHttpSession) reader.getRequest().getSession()))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongPassword_orAnUnknownName_isRefused() throws Exception {
        assertThat(signIn(TestAccounts.READER, "wrong").getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signIn("nobody", TestAccounts.PASSWORD).getResponse().getRedirectedUrl())
                .isEqualTo("/login?error");
    }

    @Test
    void thereIsALogout_withThePasswordForm() throws Exception {
        MockHttpSession session = (MockHttpSession) signIn(TestAccounts.READER, TestAccounts.PASSWORD)
                .getRequest().getSession();
        mockMvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(redirectedUrl("/login?logout"));
    }
}
