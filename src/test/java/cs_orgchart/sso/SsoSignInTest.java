package cs_orgchart.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who gets in after Microsoft: everyone of the allowed domains as USER, the lists add roles; refusals say why. */
@ExtendWith(OutputCaptureExtension.class)
class SsoSignInTest extends SsoTestSupport {

    private static Authentication authentication(SignIn signIn) {
        MockHttpSession session = signIn.session();
        assertThat(session).isNotNull();
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).as("signed in").isNotNull();
        return context.getAuthentication();
    }

    @Test
    void aColleague_getsIn_asUser_andBackToThePageAskedFor() throws Exception {
        SignIn signIn = signIn("/function.html?cs=People", TestIdp.Person.of("someone@corp.example"));
        assertThat(signIn.redirect()).contains("/function.html?cs=People");
        Authentication authentication = authentication(signIn);
        assertThat(authentication.getName()).isEqualTo("someone@corp.example");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");

        mockMvc.perform(get("/index.html").session(signIn.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/functions").session(signIn.session())).andExpect(status().isOk());
    }

    @Test
    void theAddressLists_addRoles_byEmailOrUpn() throws Exception {
        assertThat(authentication(signIn("/", TestIdp.Person.of("Admin@Corp.Example"))).getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_USER", "ROLE_SYSTEM_ADMIN");

        // The UPN (claim "name") is on the list, the e-mail claim is another address of the same person
        Map<String, String> claims = new LinkedHashMap<>(TestIdp.Person.of("hr.alias@corp.example").claims());
        claims.put(TestIdp.NAME_CLAIM, "hr@corp.example");
        assertThat(authentication(signIn("/", new TestIdp.Person("hr.alias@corp.example", claims))).getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_USER", "ROLE_HR_EDITOR");
    }

    @Test
    void anotherDomain_isRefused_andTheLogHasNamesNotValues(CapturedOutput output) throws Exception {
        SignIn signIn = signIn("/", TestIdp.Person.of("guest@partner.example"));
        assertThat(signIn.redirect()).isEqualTo("/access-not-granted.html");
        assertThat(signIn.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        assertThat(output.getOut())
                .contains("SSO sign-in refused: reason=DOMAIN_NOT_ALLOWED")
                .contains(TestIdp.EMAIL_CLAIM).contains(TestIdp.OBJECT_ID_CLAIM).contains(TestIdp.GIVEN_NAME_CLAIM)
                .doesNotContain("guest@partner.example").doesNotContain("partner.example");
    }

    @Test
    void anAnswerWithoutAddressAttributes_isRefused_whenDomainsAreChecked(CapturedOutput output) throws Exception {
        SignIn signIn = signIn("/", TestIdp.Person.withoutClaims("opaque-name-id-1234"));
        assertThat(signIn.redirect()).isEqualTo("/access-not-granted.html");
        assertThat(output.getOut()).contains("SSO sign-in refused: reason=NO_ADDRESS")
                .contains("attributes=[]").doesNotContain("opaque-name-id-1234");
    }

    @Test
    void anAnswerForAnotherAudience_failsTheSignIn(CapturedOutput output) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(TestIdp.SIGN_ON_PATH).session(session));
        String answer = TestIdp.signed(TestIdp.Person.of("someone@corp.example"), authnRequestId(session),
                "https://another.app.example/saml");
        String redirect = mockMvc.perform(post(TestIdp.ACS_PATH).session(session).param("SAMLResponse", answer))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).isEqualTo("/sign-in-failed.html");
        assertThat(output.getOut()).contains("SSO sign-in failed: invalid_assertion")
                .doesNotContain("someone@corp.example");
    }

    @Test
    void anAnswerNobodyAskedFor_failsTheSignIn() throws Exception {
        String answer = TestIdp.signed(TestIdp.Person.of("someone@corp.example"), "_never-sent");
        String redirect = mockMvc.perform(post(TestIdp.ACS_PATH).param("SAMLResponse", answer))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).isEqualTo("/sign-in-failed.html");
    }

    @Test
    void theRefusalAndFailurePages_areOpen_andSayNothingMore() throws Exception {
        String refusal = mockMvc.perform(get("/access-not-granted.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("Access is not granted").doesNotContain("href=\"/\"");
        mockMvc.perform(get("/sign-in-failed.html")).andExpect(status().isOk());
    }
}
