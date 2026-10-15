package cs_orgchart.sso;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SSO on: pages lead to Microsoft, scripts get 401, only the ACS goes without the CSRF token, no form, no logout. */
class SsoChainTest extends SsoTestSupport {

    private static final String VISIT = "{\"pageName\":\"x\",\"visitorId\":\"v\"}";

    @Test
    void aPage_withoutASession_goesToTheMicrosoftSignIn() throws Exception {
        for (String path : new String[]{"/", "/index.html", "/function.html", "/photos/default.jpg"}) {
            mockMvc.perform(get(path).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrlPattern("**" + TestIdp.SIGN_ON_PATH));
        }
    }

    @Test
    void theSignOnUrl_sendsAnAuthnRequestToTheIdentityProvider() throws Exception {
        MvcResult result = mockMvc.perform(get(TestIdp.SIGN_ON_PATH)).andExpect(status().isFound()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).startsWith(TestIdp.IDP_SSO_URL + "?SAMLRequest=");
    }

    @Test
    void anApiCall_andTheSseStream_withoutASession_are401Json() throws Exception {
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mockMvc.perform(get("/api/org/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void theServiceProviderMetadata_namesTheIdentifierAndTheReplyUrl() throws Exception {
        String metadata = mockMvc.perform(get("/saml2/metadata/entra"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metadata).contains("entityID=\"" + TestIdp.ENTITY_ID + "\"")
                .contains("Location=\"" + TestIdp.ACS_URL + "\"");
    }

    /**
     * Only Microsoft's POST to the Assertion Consumer Service comes without OrgChart's token (here a broken answer:
     * it reaches the SAML check, not the CSRF one). Every write of /api/** still needs the token after SSO.
     */
    @Test
    void withoutTheCsrfToken_onlyTheAssertionConsumerService_isAccepted() throws Exception {
        String acs = mockMvc.perform(post(TestIdp.ACS_PATH).param("SAMLResponse", "bm90IGEgU0FNTCBhbnN3ZXI="))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(acs).isEqualTo("/sign-in-failed.html");

        MockHttpSession session = signIn("/", TestIdp.Person.of("writer@corp.example")).session();
        mockMvc.perform(post("/api/page-visits").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(TestIdp.SIGN_ON_PATH).session(session)).andExpect(status().isForbidden());

        Cookie token = csrfCookie(session);
        mockMvc.perform(post("/api/page-visits").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(VISIT))
                .andExpect(status().isOk());
    }

    @Test
    void thereIsNoPasswordForm_andNoLogout() throws Exception {
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrlPattern("**" + TestIdp.SIGN_ON_PATH));

        MockHttpSession session = signIn("/", TestIdp.Person.of("someone@corp.example")).session();
        Cookie token = csrfCookie(session);
        int logout = mockMvc.perform(post("/logout").session(session).cookie(token)
                .header("X-XSRF-TOKEN", token.getValue())).andReturn().getResponse().getStatus();
        assertThat(logout).isIn(404, 405);
        mockMvc.perform(get("/api/functions").session(session)).andExpect(status().isOk());
    }
}
