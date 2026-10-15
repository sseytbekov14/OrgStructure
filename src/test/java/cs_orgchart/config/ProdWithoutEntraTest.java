package cs_orgchart.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The way back: prod with AUTH_SSO_ENABLED=false starts without anything of Entra (no SAML settings, no metadata)
 * and the site is open to everyone without sign-in, as before SSO. Writes still need the page's CSRF token.
 */
@SpringBootTest(properties = "auth.sso.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
class ProdWithoutEntraTest {

    private static final String VISIT = "{\"pageName\":\"x\",\"visitorId\":\"v\"}";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void startsWithoutEntra_andEverythingOpensWithoutSignIn() throws Exception {
        assertThat(context.getBeanNamesForType(RelyingPartyRegistrationRepository.class)).isEmpty();
        mockMvc.perform(get("/index.html").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
        mockMvc.perform(get("/function.html").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
        mockMvc.perform(get("/api/employees")).andExpect(status().isOk());
        mockMvc.perform(get("/api/functions")).andExpect(status().isOk());
        mockMvc.perform(get("/api/likes/summary")).andExpect(status().isOk());
    }

    @Test
    void thereIsNoSignIn_noSso_noLogout() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isNotFound());
        mockMvc.perform(get("/saml2/authenticate/entra")).andExpect(status().isNotFound());
        mockMvc.perform(get("/saml2/metadata/entra")).andExpect(status().isNotFound());
        mockMvc.perform(get("/me")).andExpect(status().isNotFound());
        int logout = mockMvc.perform(post("/logout")).andReturn().getResponse().getStatus();
        assertThat(logout).isIn(403, 404, 405);
    }

    @Test
    void aWrite_needsThePagesCsrfToken_asBefore() throws Exception {
        mockMvc.perform(post("/api/page-visits").contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isForbidden());
        Cookie token = mockMvc.perform(get("/index.html")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        mockMvc.perform(post("/api/page-visits").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isOk());
    }
}
