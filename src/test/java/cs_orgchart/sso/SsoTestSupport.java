package cs_orgchart.sso;

import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SSO as it is deployed (AUTH_SSO_ENABLED=true), against the made-up identity provider of {@link TestIdp}.
 * Every SSO test class extends this, so they share one application context. Admitted domain: corp.example;
 * admin@corp.example is SYSTEM_ADMIN, hr@corp.example is HR_EDITOR.
 */
@SpringBootTest(properties = {
        "auth.sso.enabled=true",
        "auth.sso.entity-id=" + TestIdp.ENTITY_ID,
        "auth.sso.acs-url=" + TestIdp.ACS_URL,
        "auth.sso.allowed-email-domains=Corp.Example",
        "auth.roles.system-admin-emails=admin@corp.example",
        "auth.roles.hr-editor-emails=hr@corp.example"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class SsoTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("auth.sso.metadata-uri", TestIdp::metadataUri);
    }

    /** The answer of the Assertion Consumer Service. */
    protected record SignIn(MvcResult result) {

        public MockHttpSession session() {
            return (MockHttpSession) result.getRequest().getSession(false);
        }

        public String redirect() {
            return result.getResponse().getRedirectedUrl();
        }
    }

    /**
     * The browser's way in: open {@code page} without a session (OrgChart remembers it and sends the browser to
     * the Sign on URL), the Sign on URL (an AuthnRequest, kept in the session), then the identity provider posts
     * its signed answer to the Assertion Consumer Service - without OrgChart's CSRF token, as Microsoft does.
     */
    protected SignIn signIn(String page, TestIdp.Person person) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(page).session(session));
        mockMvc.perform(get(TestIdp.SIGN_ON_PATH).session(session));
        return new SignIn(mockMvc.perform(post(TestIdp.ACS_PATH).session(session)
                .param("SAMLResponse", TestIdp.signed(person, authnRequestId(session)))).andReturn());
    }

    /** The id of the AuthnRequest the session waits an answer for. */
    protected static String authnRequestId(MockHttpSession session) {
        return Collections.list(session.getAttributeNames()).stream()
                .map(session::getAttribute)
                .filter(AbstractSaml2AuthenticationRequest.class::isInstance)
                .map(request -> ((AbstractSaml2AuthenticationRequest) request).getId())
                .findFirst().orElseThrow(() -> new IllegalStateException("no AuthnRequest in the session"));
    }

    /** The XSRF-TOKEN cookie a page gives the browser (never spring-security-test's csrf(), see CsrfAndSessionTest). */
    protected Cookie csrfCookie(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/index.html").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }
}
