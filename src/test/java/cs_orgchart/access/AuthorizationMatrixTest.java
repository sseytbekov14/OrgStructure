package cs_orgchart.access;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * U7 security review: every /api/manage/** and /api/admin/** endpoint, found in the handler mappings (a new one is
 * covered without touching this test), for anonymous, a signed-in non-admin, a database admin and an env super admin;
 * CSRF on every write; no admin data in the answers non-admins get.
 */
class AuthorizationMatrixTest extends DbModeMvcSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private record Endpoint(HttpMethod method, String pattern, String url, Who who) {
        boolean write() {
            return method != HttpMethod.GET;
        }
    }

    /** Who may call it, from its @PreAuthorize */
    private enum Who { ADMINS, SUPER_ADMINS }

    private List<Endpoint> endpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            PreAuthorize check = handler.getMethodAnnotation(PreAuthorize.class);
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/manage") && !pattern.startsWith("/api/admin")) {
                    continue;
                }
                assertThat(check).as("@PreAuthorize on %s", handler).isNotNull();
                Who who = check.value().contains("isAdmin(") ? Who.ADMINS : Who.SUPER_ADMINS;
                if (pattern.startsWith("/api/admin")) {
                    assertThat(who).as("/api/admin/** stays for env super admins: %s", handler).isEqualTo(Who.SUPER_ADMINS);
                }
                Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
                for (RequestMethod method : methods) {
                    // An id that exists nowhere: a call must never change the fixture (e.g. DELETE /api/manage/admins/1 removed the
                    // editor's own admin row on a fresh database)
                    String url = pattern.replace("{cs}", "ITS").replaceAll("\\{[^}]+}", "987654321");
                    endpoints.add(new Endpoint(HttpMethod.valueOf(method.name()), pattern, url, who));
                }
            }
        }
        return endpoints;
    }

    private int call(Endpoint endpoint, MockHttpSession session, boolean csrf) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(endpoint.method(), endpoint.url())
                .param("cs", "ITS").param("dryRun", "true");
        if (endpoint.write()) {
            request.contentType("application/json").content("{}");
        }
        if (session != null) {
            request.session(session);
        }
        if (csrf) {
            request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void theMatrixCoversEveryEndpoint() {
        List<Endpoint> endpoints = endpoints();
        assertThat(endpoints).extracting(Endpoint::pattern).contains("/api/manage/overrides", "/api/manage/admins",
                "/api/manage/functions/{cs}/groups", "/api/manage/functions/{cs}/faq", "/api/manage/hidden",
                "/api/admin/ad-sync", "/api/admin/ad-sync/log");
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void everyEndpointByRole() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            expect(wrong, e, "anonymous", call(e, null, true), Set.of(401));
            expect(wrong, e, "non-admin", call(e, reader, true), Set.of(403));
            int asEditor = call(e, editor, true);
            if (e.who() == Who.ADMINS) {
                expectNot(wrong, e, "db admin", asEditor);
            } else {
                expect(wrong, e, "db admin", asEditor, Set.of(403));
            }
            expectNot(wrong, e, "super admin", call(e, superAdmin, true));
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void everyWriteNeedsTheCsrfToken() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (e.write()) {
                expect(wrong, e, "super admin without CSRF token", call(e, superAdmin, false), Set.of(403));
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void aDisabledDatabaseAdminIsANonAdminEverywhere() throws Exception {
        jdbc.update("UPDATE app_admins SET enabled = FALSE");
        adminAccess.evict();
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            expect(wrong, e, "disabled db admin", call(e, editor, true), Set.of(403));
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void nonAdminsGetNoAdminData() throws Exception {
        String roles = mvc.perform(get("/api/me/roles").session(reader)).andReturn().getResponse().getContentAsString();
        assertThat(roles).contains("\"admin\":false", "\"superAdmin\":false").doesNotContain("SYSTEM_ADMIN");
        for (String url : List.of("/api/employees", "/api/functions", "/api/search?q=it", "/api/functions/ITS/faq")) {
            String body = mvc.perform(get(url).session(reader)).andReturn().getResponse().getContentAsString();
            assertThat(body).as(url).doesNotContain("updatedBy", "updated_by", "hiddenBy", "loginName", "addedBy",
                    "\"version\":{", "\"override\"", "\"phone\"", "\"mobile\"", "\"office\"");
        }
    }

    private static void expect(List<String> wrong, Endpoint e, String who, int status, Set<Integer> allowed) {
        if (!allowed.contains(status)) {
            wrong.add(e.method() + " " + e.url() + " as " + who + ": " + status + ", expected " + allowed);
        }
    }

    private static void expectNot(List<String> wrong, Endpoint e, String who, int status) {
        if (status == 401 || status == 403) {
            wrong.add(e.method() + " " + e.url() + " as " + who + ": " + status + ", expected access");
        }
    }
}
