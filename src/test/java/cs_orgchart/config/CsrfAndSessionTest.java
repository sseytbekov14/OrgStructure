package cs_orgchart.config;

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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF (cookie + X-XSRF-TOKEN), 401 JSON for scripts and the SSE stream, the page to come back to.
 * The token is taken the way the browser does (the XSRF-TOKEN cookie), never with spring-security-test's csrf():
 * that one swaps the token repository of the shared context for a session-based one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfAndSessionTest {

    private static final String VISIT = "{\"pageName\":\"x\",\"visitorId\":\"v\"}";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("auth.password.enabled", () -> "true");
        registry.add("auth.password.users", TestAccounts::users);
    }

    /** The XSRF-TOKEN cookie a page (here the sign-in form) gives the browser. */
    private Cookie csrfCookie() throws Exception {
        Cookie token = mockMvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return token;
    }

    private MvcResult signIn(MockHttpSession session) throws Exception {
        Cookie token = csrfCookie();
        return mockMvc.perform(post("/login").session(session).cookie(token).param("_csrf", token.getValue())
                .param("username", TestAccounts.READER).param("password", TestAccounts.PASSWORD)).andReturn();
    }

    private MockHttpSession signIn() throws Exception {
        return (MockHttpSession) signIn(new MockHttpSession()).getRequest().getSession();
    }

    @Test
    void anApiCall_withoutASession_is401Json_notARedirect() throws Exception {
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Session expired, reload the page"));

        // The token cookie outlives the session: a page's write after the session ended is 401 too
        Cookie token = csrfCookie();
        mockMvc.perform(post("/api/page-visits").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSseStream_withoutASession_is401Json() throws Exception {
        mockMvc.perform(get("/api/org/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void theSseStream_withASession_isOpened() throws Exception {
        mockMvc.perform(get("/api/org/stream").accept(MediaType.TEXT_EVENT_STREAM).session(signIn()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void aWrite_withoutTheToken_isRefused_evenWithASession() throws Exception {
        MockHttpSession session = signIn();
        mockMvc.perform(post("/api/page-visits").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/likes").session(session)
                        .param("employeeEmail", "a@corp.example").param("reactionType", "SOLVED"))
                .andExpect(status().isForbidden());
    }

    /** As the browser does it: the page sets the XSRF-TOKEN cookie, the script sends it back as the header. */
    @Test
    void aWrite_withTheCookieTokenAsHeader_isAccepted() throws Exception {
        MockHttpSession session = signIn();
        MvcResult page = mockMvc.perform(get("/index.html").session(session)).andExpect(status().isOk()).andReturn();
        Cookie token = page.getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        assertThat(token.isHttpOnly()).isFalse();

        mockMvc.perform(post("/api/page-visits").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/page-visits").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isForbidden());
    }

    @Test
    void afterTheSignIn_thePageAskedFor_opens_butNeverAnApiCall() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/function.html?cs=People").session(session))
                .andExpect(redirectedUrlPattern("**/login"));
        assertThat(signIn(session).getResponse().getRedirectedUrl()).contains("/function.html?cs=People");

        MockHttpSession script = new MockHttpSession();
        mockMvc.perform(get("/api/employees").session(script)).andExpect(status().isUnauthorized());
        assertThat(signIn(script).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void photos_needASignIn() throws Exception {
        mockMvc.perform(get("/photos/default.jpg").accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
