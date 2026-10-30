package cs_orgchart.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The open site (no sign-in): no other origin may read the employees through a visitor's browser, the API map is not
 * published, a like needs a visitor id, the CSP forbids plugins, base changes, framing and (except on the pages Spring
 * writes itself) inline scripts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HardeningTest {

    @Autowired
    private MockMvc mockMvc;

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/functions")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    @Test
    void anotherOrigin_getsNoCorsHeaders() throws Exception {
        for (String url : new String[] {"/api/employees", "/api/functions", "/api/search?q=a", "/api/likes/summary"}) {
            mockMvc.perform(get(url).header(HttpHeaders.ORIGIN, "https://evil.example"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void theApiDescription_isOffOutsideLocal() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void aLike_needsAVisitorId() throws Exception {
        mockMvc.perform(withToken(post("/api/likes")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeEmail\":\"a@corp.example\",\"reactionType\":\"SOLVED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withToken(post("/api/likes")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeEmail\":\"a@corp.example\",\"reactionType\":\"SOLVED\",\"visitorId\":\""
                                + "v".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        String like = "{\"employeeEmail\":\"a@corp.example\",\"reactionType\":\"SOLVED\",\"visitorId\":\"hardening\"}";
        mockMvc.perform(withToken(post("/api/likes")).contentType(MediaType.APPLICATION_JSON).content(like))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/likes")).contentType(MediaType.APPLICATION_JSON).content(like))
                .andExpect(status().isConflict());
    }

    private String csp(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn().getResponse().getHeaders("Content-Security-Policy")
                .stream().reduce((a, b) -> a + " | " + b).orElseThrow();
    }

    @Test
    void theCsp_forbidsPluginsBaseAndFraming() throws Exception {
        assertThat(csp("/api/functions")).contains("object-src 'none'", "base-uri 'self'", "frame-ancestors 'none'");
    }

    @Test
    void theSitesPages_runNoInlineScript_onlySpringsOwnPagesDo() throws Exception {
        for (String url : new String[] {"/", "/index.html", "/function.html", "/admin.html", "/api/functions"}) {
            assertThat(csp(url)).as(url).isEqualTo(SecurityConfig.SCRIPTS_SELF_ONLY).contains("script-src 'self';");
        }
        assertThat(csp("/login")).isEqualTo(SecurityConfig.SCRIPTS_INLINE);
        assertThat(csp(SamlConfig.SIGN_ON_PATH)).isEqualTo(SecurityConfig.SCRIPTS_INLINE);
    }

    /** The strict CSP blocks them: an onclick="..." in a page or a script's markup would silently do nothing */
    @Test
    void thePagesAndScripts_haveNoInlineHandlersOrScripts() throws IOException {
        Pattern inline = Pattern.compile("\\son[a-z]+\\s*=\\s*[\"'`]|<script(?![^>]*\\ssrc=)[^>]*>|javascript:[^,]");
        List<Path> files;
        try (Stream<Path> walk = Stream.concat(Files.walk(Path.of("src/main/resources/static")),
                Files.walk(Path.of("src/main/resources/pages")))) {
            files = walk.filter(p -> p.toString().endsWith(".js") || p.toString().endsWith(".html")).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String text = Files.readString(file);
            assertThat(inline.matcher(text).results().map(MatchResult::group).toList()).as(file.toString()).isEmpty();
        }
    }
}
