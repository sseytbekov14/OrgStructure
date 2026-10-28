package cs_orgchart.access;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U1: admin access from the database. Every /api/manage/** endpoint for anonymous (401), a signed-in non-admin (403),
 * a database admin (allowed), a disabled database admin (403) and an env super admin (allowed); the admin list for
 * super admins only; the AD sync still for env super admins only; CSRF; the shape of /api/me/roles.
 */
class ManageAccessTest extends DbModeMvcSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private record Call(String name, Supplier<MockHttpServletRequestBuilder> request, Object body, boolean write) {
    }

    /** Every content-editing endpoint (U1), with a request that passes validation for an admin */
    private List<Call> editingCalls() {
        long mmint = id("mmint");
        return List.of(
                new Call("GET overrides", () -> get("/api/manage/overrides").param("cs", "ITS"), null, false),
                new Call("PUT override", () -> put("/api/manage/overrides/" + mmint).param("cs", "ITS"),
                        Map.of("groupName", "Service Desk", "areaOfDuties", "Workplace IT",
                                "jobDescription", "User support", "version", 0), true),
                new Call("DELETE override", () -> delete("/api/manage/overrides/" + mmint).param("cs", "ITS")
                        .param("version", "999"), null, true),
                new Call("GET heads", () -> get("/api/manage/functions/ITS/heads"), null, false),
                new Call("PUT heads", () -> put("/api/manage/functions/ITS/heads"),
                        Map.of("employeeIds", List.of(id("kkhaki"), id("llemon")),
                                "expectedHeads", List.of(id("kkhaki"), id("llemon"))), true));
    }

    private int perform(Call call, MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = call.request().get();
        if (call.write()) {
            write(request, session, call.body());
        } else if (session != null) {
            request.session(session);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void anonymousIs401OnEveryManageEndpoint() throws Exception {
        for (Call call : editingCalls()) {
            assertThat(perform(call, null)).as(call.name()).isEqualTo(401);
        }
        mvc.perform(get("/api/manage/admins")).andExpect(status().isUnauthorized());
    }

    @Test
    void signedInNonAdminIs403OnEveryManageEndpoint() throws Exception {
        for (Call call : editingCalls()) {
            assertThat(perform(call, reader)).as(call.name()).isEqualTo(403);
        }
    }

    @Test
    void databaseAdminAndEnvSuperAdminAreAllowed() throws Exception {
        for (MockHttpSession allowed : List.of(editor, superAdmin)) {
            for (Call call : editingCalls()) {
                assertThat(perform(call, allowed)).as(call.name()).isNotIn(401, 403);
            }
        }
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(editor)).andExpect(status().isOk());
        mvc.perform(get("/api/manage/functions/ITS/heads").session(editor)).andExpect(status().isOk());
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), editor, editingCalls().get(4).body()))
                .andExpect(status().isOk());
    }

    @Test
    void theDatabaseAdminIsWrittenAsUpdatedBy() throws Exception {
        long mmint = id("mmint");
        long version = jdbc.queryForObject("SELECT version FROM employee_overrides WHERE employee_id = ? AND cs = 'ITS'",
                Long.class, mmint);
        mvc.perform(write(put("/api/manage/overrides/" + mmint).param("cs", "ITS"), editor,
                        Map.of("groupName", "Service Desk", "areaOfDuties", "Edited by the editor",
                                "jobDescription", "User support", "version", version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy").value(TestAccounts.EDITOR));
    }

    @Test
    void aDisabledDatabaseAdminIs403() throws Exception {
        jdbc.update("UPDATE app_admins SET enabled = FALSE");
        adminAccess.evict();
        for (Call call : editingCalls()) {
            assertThat(perform(call, editor)).as(call.name()).isEqualTo(403);
        }
        mvc.perform(get("/api/me/roles").session(editor)).andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void theAdminListIsForSuperAdminsOnly() throws Exception {
        mvc.perform(get("/api/manage/admins").session(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/manage/admins").session(editor)).andExpect(status().isForbidden());
        mvc.perform(write(post("/api/manage/admins"), editor, Map.of("loginName", TestAccounts.READER)))
                .andExpect(status().isForbidden());
        long editorRow = jdbc.queryForObject("SELECT id FROM app_admins", Long.class);
        mvc.perform(write(delete("/api/manage/admins/" + editorRow), editor, null)).andExpect(status().isForbidden());

        mvc.perform(get("/api/manage/admins").session(superAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].loginName").value(TestAccounts.EDITOR.toUpperCase()))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void addingAndRemovingTakesEffectAtOnce() throws Exception {
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(reader)).andExpect(status().isForbidden());
        JsonNode added = body(mvc.perform(write(post("/api/manage/admins"), superAdmin,
                        Map.of("loginName", "READER", "note", "access for the test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginName").value("READER"))
                .andExpect(jsonPath("$.addedBy").value(TestAccounts.ADMIN))
                .andReturn());
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(reader)).andExpect(status().isOk());
        mvc.perform(get("/api/me/roles").session(reader)).andExpect(jsonPath("$.admin").value(true));

        mvc.perform(write(delete("/api/manage/admins/" + added.get("id").asLong()), superAdmin, null))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/roles").session(reader)).andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void theAdminListValidatesItsInput() throws Exception {
        for (String bad : List.of("ab", "with space", "<script>", "name;drop", "x".repeat(257), "")) {
            mvc.perform(write(post("/api/manage/admins"), superAdmin, Map.of("loginName", bad)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(write(post("/api/manage/admins"), superAdmin, Map.of("loginName", "ok.name", "note", "<b>")))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/manage/admins"), superAdmin, Map.of("loginName", "editor@CORP.example")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_EXISTS"));
        mvc.perform(write(delete("/api/manage/admins/987654"), superAdmin, null)).andExpect(status().isNotFound());
    }

    @Test
    void atMostFiftyEnabledAdmins() throws Exception {
        for (int i = 1; i < AppAdminService.MAX_ENABLED; i++) {
            jdbc.update("INSERT INTO app_admins (login_name) VALUES (?)", "admin" + i + "@corp.example");
        }
        mvc.perform(write(post("/api/manage/admins"), superAdmin, Map.of("loginName", "one.too.many")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_LIMIT"));
    }

    @Test
    void aDisabledRowIsEnabledAgainWhenAdded() throws Exception {
        jdbc.update("UPDATE app_admins SET enabled = FALSE");
        adminAccess.evict();
        mvc.perform(write(post("/api/manage/admins"), superAdmin, Map.of("loginName", TestAccounts.EDITOR)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_admins", Long.class)).isEqualTo(1);
        mvc.perform(get("/api/manage/overrides").param("cs", "ITS").session(editor)).andExpect(status().isOk());
    }

    @Test
    void theAdSyncStaysForEnvSuperAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/ad-sync/log").session(editor)).andExpect(status().isForbidden());
        mvc.perform(write(post("/api/admin/ad-sync").param("dryRun", "true"), editor, null))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ad-sync/log").session(superAdmin)).andExpect(status().isOk());
    }

    @Test
    void writesNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/api/manage/admins").session(superAdmin).contentType("application/json")
                .content("{\"loginName\":\"someone\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/manage/functions/ITS/heads").session(editor).contentType("application/json")
                .content("{\"employeeIds\":[],\"expectedHeads\":[]}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/manage/overrides/" + id("mmint")).param("cs", "ITS").session(editor))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_admins", Long.class)).isEqualTo(1);
    }

    @Test
    void meRolesHasTheAgreedShape() throws Exception {
        JsonNode me = body(mvc.perform(get("/api/me/roles").session(reader)).andExpect(status().isOk()).andReturn());
        List<String> fields = new ArrayList<>();
        Iterator<String> names = me.fieldNames();
        names.forEachRemaining(fields::add);
        assertThat(fields).containsExactly("authenticated", "you", "roles", "admin", "superAdmin", "editingEnabled");
        assertThat(me.get("you").asText()).isEqualTo(TestAccounts.READER);
        assertThat(me.get("admin").asBoolean()).isFalse();
        assertThat(me.get("superAdmin").asBoolean()).isFalse();

        mvc.perform(get("/api/me/roles").session(editor))
                .andExpect(jsonPath("$.you").value(TestAccounts.EDITOR))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.superAdmin").value(false));
        mvc.perform(get("/api/me/roles").session(superAdmin))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.superAdmin").value(true))
                .andExpect(jsonPath("$.editingEnabled").value(true));
    }

    @Test
    void theAdminPageIsServedToAdminsOnly() throws Exception {
        mvc.perform(get("/admin.html").session(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/admin.html").session(editor))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Administrators")));
        mvc.perform(get("/admin.html").session(superAdmin)).andExpect(status().isOk());
        assertThat(mvc.perform(get("/admin.html")).andReturn().getResponse().getStatus()).isIn(302, 401);
    }

    @Test
    void everyManageHandlerHasAnAdminCheck() {
        List<String> manage = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            boolean underManage = entry.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/manage"));
            if (!underManage) {
                continue;
            }
            HandlerMethod handler = entry.getValue();
            PreAuthorize check = handler.getMethodAnnotation(PreAuthorize.class);
            if (check == null) {
                check = handler.getBeanType().getAnnotation(PreAuthorize.class);
            }
            assertThat(check).as("@PreAuthorize on %s", handler).isNotNull();
            assertThat(check.value()).as(handler.toString())
                    .matches("@adminAccess\\.is(Super)?Admin\\(authentication\\)");
            manage.add(handler.toString());
        }
        assertThat(manage).as("handlers under /api/manage").hasSizeGreaterThanOrEqualTo(8);
    }
}
