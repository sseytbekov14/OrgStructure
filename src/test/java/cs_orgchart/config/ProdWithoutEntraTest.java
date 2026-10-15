package cs_orgchart.config;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The way back: prod with AUTH_SSO_ENABLED=false starts without anything of Entra (no SAML settings, no metadata),
 * with the password form only. The account below has a made-up hash nobody knows the password of.
 */
@SpringBootTest(properties = {
        "auth.sso.enabled=false",
        "auth.password.users=admin:$2a$04$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0"
})
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
class ProdWithoutEntraTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void startsWithoutEntra_withThePasswordFormOnly() throws Exception {
        assertThat(context.getBeanNamesForType(RelyingPartyRegistrationRepository.class)).isEmpty();
        mockMvc.perform(get("/login")).andExpect(status().isOk());
        mockMvc.perform(get("/index.html").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/saml2/authenticate/entra").accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/saml2/metadata/entra").accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
