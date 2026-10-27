package cs_orgchart.sso;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AUTH_SSO_DEBUG_ENDPOINT=true: GET /me shows the signed-in person what Microsoft sent about them. */
@TestPropertySource(properties = "auth.sso.debug-endpoint=true")
class SsoDebugEndpointTest extends SsoTestSupport {

    @Test
    void me_showsTheOwnClaims() throws Exception {
        MockHttpSession session = signIn("/", TestIdp.Person.of("someone@corp.example")).session();
        mockMvc.perform(get("/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("someone@corp.example"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.nameId").value("someone@corp.example"))
                .andExpect(jsonPath("$.claims['" + TestIdp.EMAIL_CLAIM + "'][0]").value("someone@corp.example"))
                .andExpect(jsonPath("$.claims['" + TestIdp.OBJECT_ID_CLAIM + "']").isArray());
    }

    @Test
    void me_withoutASession_goesToTheSignIn() throws Exception {
        mockMvc.perform(get("/me").accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrlPattern("**" + TestIdp.SIGN_ON_PATH));
    }
}
