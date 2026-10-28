package cs_orgchart.employee.group;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.employee.EmployeeDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U4: groups of a function (create, rename, reorder, delete, members) on PostgreSQL with the mock directory, and the
 * security matrix of the group endpoints.
 */
class GroupsAdminTest extends DbModeMvcSupport {

    @Autowired
    private EmployeeDirectory directory;

    @Autowired
    private FunctionGroupRepository groups;

    private JsonNode list(String cs) throws Exception {
        return body(mvc.perform(get("/api/manage/functions/" + cs + "/groups").session(editor))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode group(String cs, String name) throws Exception {
        for (JsonNode g : list(cs).get("groups")) {
            if (g.get("name").asText().equalsIgnoreCase(name)) {
                return g;
            }
        }
        throw new AssertionError("no group " + name + " in " + cs);
    }

    private long createGroup(String cs, String name, String kind) throws Exception {
        return body(mvc.perform(write(post("/api/manage/functions/" + cs + "/groups"), editor,
                        kind == null ? Map.of("name", name) : Map.of("name", name, "kind", kind)))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private String dtoGroup(String name, String cs) {
        return directory.getAllEmployees().stream().filter(e -> e.getName().equals(name) && cs.equals(e.getCs()))
                .findFirst().orElseThrow().getGroup();
    }

    // --- list --------------------------------------------------------------------------------------------------

    @Test
    void theListHasEveryGroupWithMembersHeadsAndTheirSource() throws Exception {
        JsonNode its = list("ITS");
        JsonNode pmo = group("ITS", "IT PMO");
        assertThat(pmo.get("source").asText()).as("AD default group registered").isEqualTo("AUTO");
        assertThat(pmo.get("members").findValuesAsText("name")).contains("November, Navy", "Papa, Peach");
        JsonNode peach = find(pmo.get("members"), "Papa, Peach");
        assertThat(peach.get("source").asText()).as("moved by the mock overlay").isEqualTo("MANUAL");
        assertThat(find(pmo.get("members"), "November, Navy").get("source").asText()).isEqualTo("AD");
        assertThat(its.get("heads").findValuesAsText("name")).contains("Kilo, Khaki", "Lima, Lemon");
        assertThat(pmo.get("version").isNumber()).isTrue();
    }

    private static JsonNode find(JsonNode members, String name) {
        for (JsonNode m : members) {
            if (name.equals(m.get("name").asText())) {
                return m;
            }
        }
        throw new AssertionError(name + " not listed");
    }

    // --- create / rename / reorder / delete --------------------------------------------------------------------

    @Test
    void namesAreUniquePerFunctionIgnoringCase() throws Exception {
        createGroup("ITS", "Architecture", null);
        mvc.perform(write(post("/api/manage/functions/ITS/groups"), editor, Map.of("name", "ARCHITECTURE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUP_EXISTS"));
        createGroup("QRM", "Architecture", null);
    }

    @Test
    void reservedNamesAndCountryCodesAreRefused() throws Exception {
        for (String name : List.of("HEAD", "Other", "head")) {
            mvc.perform(write(post("/api/manage/functions/ITS/groups"), editor, Map.of("name", name)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name.code").value("RESERVED"));
        }
        createGroup("ITS", "uz", "COUNTRY");
        assertThat(group("ITS", "UZ").get("kind").asText()).isEqualTo("COUNTRY");
        mvc.perform(write(post("/api/manage/functions/ITS/groups"), editor, Map.of("name", "Uz", "kind", "TEAM")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name.code").value("RESERVED"));
        mvc.perform(write(post("/api/manage/functions/ITS/groups"), editor, Map.of("name", "XX", "kind", "COUNTRY")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name.code").value("COUNTRY_CODE"));
        mvc.perform(write(post("/api/manage/functions/ITS/groups"), editor, Map.of("name", "x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name.code").value("TOO_SHORT"));
        mvc.perform(write(post("/api/manage/functions/ITS/groups"), editor, Map.of("name", "<b>Team</b>")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name.code").value("MARKUP"));
    }

    @Test
    void renameShowsEverywhereButAutoGroupsKeepTheirName() throws Exception {
        long id = createGroup("ITS", "Service Desk Team", null);
        mvc.perform(write(post("/api/manage/groups/" + id + "/members"), editor,
                Map.of("employeeIds", List.of(id("mmint"))))).andExpect(status().isOk());
        long version = group("ITS", "Service Desk Team").get("version").asLong();
        mvc.perform(write(put("/api/manage/groups/" + id), editor, Map.of("name", "Workplace", "version", version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Workplace"));
        assertThat(dtoGroup("Mike, Mint", "ITS")).isEqualTo("Workplace");

        JsonNode auto = group("ITS", "IT PMO");
        mvc.perform(write(put("/api/manage/groups/" + auto.get("id").asLong()), editor,
                        Map.of("name", "Projects", "version", auto.get("version").asLong())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTO_GROUP_NAME"));
    }

    @Test
    void aStaleVersionIs409() throws Exception {
        long id = createGroup("ITS", "Security", null);
        long version = group("ITS", "Security").get("version").asLong();
        mvc.perform(write(put("/api/manage/groups/" + id), editor, Map.of("name", "Security Ops", "version", version)))
                .andExpect(status().isOk());
        mvc.perform(write(put("/api/manage/groups/" + id), editor, Map.of("name", "Sec", "version", version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGED"));
        mvc.perform(write(delete("/api/manage/groups/" + id).param("version", String.valueOf(version)), editor, null))
                .andExpect(status().isConflict());
    }

    @Test
    void positionsAreRenumbered() throws Exception {
        long a = createGroup("QRM", "Alpha", null);
        long b = createGroup("QRM", "Beta", null);
        long version = group("QRM", "Beta").get("version").asLong();
        mvc.perform(write(put("/api/manage/groups/" + b), editor, Map.of("position", 0, "version", version)))
                .andExpect(status().isOk());
        List<String> names = list("QRM").get("groups").findValuesAsText("name");
        assertThat(names.indexOf("Beta")).isLessThan(names.indexOf("Alpha"));
        assertThat(groups.findById(a)).isPresent();
    }

    @Test
    void aGroupWithMembersCannotBeDeletedOrDeactivatedAnEmptyOneCan() throws Exception {
        JsonNode pmo = group("ITS", "IT PMO");
        mvc.perform(write(delete("/api/manage/groups/" + pmo.get("id").asLong())
                        .param("version", pmo.get("version").asText()), editor, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_EMPTY"));
        mvc.perform(write(put("/api/manage/groups/" + pmo.get("id").asLong()), editor,
                        Map.of("active", false, "version", pmo.get("version").asLong())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_EMPTY"));

        long empty = createGroup("ITS", "Empty one", null);
        mvc.perform(write(delete("/api/manage/groups/" + empty)
                        .param("version", group("ITS", "Empty one").get("version").asText()), editor, null))
                .andExpect(status().isNoContent());
        assertThat(groups.findById(empty)).isEmpty();
    }

    // --- members -----------------------------------------------------------------------------------------------

    @Test
    void addingMovesThePersonAndTellsWhereTheyWere() throws Exception {
        long target = createGroup("ITS", "Platform", null);
        JsonNode result = body(mvc.perform(write(post("/api/manage/groups/" + target + "/members"), editor,
                Map.of("employeeIds", List.of(id("ppeach"), id("nnavy"))))).andExpect(status().isOk()).andReturn());
        JsonNode peach = result.get("moved").get(0);
        assertThat(peach.get("previousGroupName").asText()).isEqualTo("IT PMO");
        assertThat(result.get("moved").get(1).get("previousGroupId").isNull()).as("AD default before").isTrue();
        assertThat(dtoGroup("Papa, Peach", "ITS")).isEqualTo("Platform");
        assertThat(dtoGroup("November, Navy", "ITS")).isEqualTo("Platform");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_overrides WHERE employee_id = ? AND cs = 'ITS'",
                Long.class, id("ppeach"))).as("one group per function").isEqualTo(1);
    }

    @Test
    void addingIsAtomic() throws Exception {
        long target = createGroup("ITS", "Platform", null);
        mvc.perform(write(post("/api/manage/groups/" + target + "/members"), editor,
                        Map.of("employeeIds", List.of(id("nnavy"), 987654321L))))
                .andExpect(status().isNotFound());
        assertThat(dtoGroup("November, Navy", "ITS")).as("nothing changed").isEqualTo("IT PMO");
        mvc.perform(write(post("/api/manage/groups/" + target + "/members"), editor,
                        Map.of("employeeIds", java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY"));
    }

    @Test
    void removingReturnsToTheAdDefaultGroupAndIsRefusedInTheAdDefaultGroup() throws Exception {
        JsonNode pmo = group("ITS", "IT PMO");
        long pmoId = pmo.get("id").asLong();
        mvc.perform(write(delete("/api/manage/groups/" + pmoId + "/members/" + id("nnavy")), editor, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AD_DEFAULT_GROUP"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "belongs here by Active Directory, move them to another group")));
        mvc.perform(write(delete("/api/manage/groups/" + pmoId + "/members/" + id("ppeach")), editor, null))
                .andExpect(status().isNoContent());
        assertThat(dtoGroup("Papa, Peach", "ITS")).as("no AD default group for Peach").isNull();
    }

    @Test
    void movingIntoTheAdDefaultGroupStoresNoManualGroup() throws Exception {
        long platform = createGroup("ITS", "Platform", null);
        mvc.perform(write(post("/api/manage/groups/" + platform + "/members"), editor,
                Map.of("employeeIds", List.of(id("nnavy"))))).andExpect(status().isOk());
        long pmo = group("ITS", "IT PMO").get("id").asLong();
        mvc.perform(write(post("/api/manage/groups/" + pmo + "/members"), editor,
                Map.of("employeeIds", List.of(id("nnavy"))))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT group_id FROM employee_overrides WHERE employee_id = ? AND cs = 'ITS'",
                Long.class, id("nnavy"))).isNull();
        assertThat(dtoGroup("November, Navy", "ITS")).isEqualTo("IT PMO");
    }

    @Test
    void theGroupOrderIsOnTheEmployees() throws Exception {
        JsonNode pmo = group("ITS", "IT PMO");
        assertThat(directory.getAllEmployees().stream().filter(e -> "November, Navy".equals(e.getName()))
                .findFirst().orElseThrow().getGroupOrder()).isEqualTo(pmo.get("sortOrder").asInt());
    }

    @Test
    void theOverrideEditUsesGroupObjects() throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM employee_overrides WHERE employee_id = ? AND cs = 'ITS'",
                Long.class, id("mmint"));
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        Map.of("groupName", "it pmo", "version", version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupName").value("IT PMO"));
        assertThat(dtoGroup("Mike, Mint", "ITS")).isEqualTo("IT PMO");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        Map.of("groupName", "Other", "version", version + 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name.code").value("RESERVED"));
    }

    // --- access ------------------------------------------------------------------------------------------------

    private record Call(String name, Supplier<MockHttpServletRequestBuilder> request, Object body, boolean write) {
    }

    @Test
    void everyGroupEndpointIsForAdminsOnly() throws Exception {
        long pmo = group("ITS", "IT PMO").get("id").asLong();
        List<Call> calls = List.of(
                new Call("GET groups", () -> get("/api/manage/functions/ITS/groups"), null, false),
                new Call("POST group", () -> post("/api/manage/functions/ITS/groups"), Map.of("name", "Matrix team"), true),
                new Call("PUT group", () -> put("/api/manage/groups/" + pmo), Map.of("position", 0, "version", 999), true),
                new Call("DELETE group", () -> delete("/api/manage/groups/" + pmo).param("version", "999"), null, true),
                new Call("POST members", () -> post("/api/manage/groups/" + pmo + "/members"),
                        Map.of("employeeIds", List.of(id("mmint"))), true),
                new Call("DELETE member", () -> delete("/api/manage/groups/" + pmo + "/members/" + id("nnavy")), null, true));
        for (Call call : calls) {
            assertThat(perform(call, null)).as("anonymous " + call.name()).isEqualTo(401);
            assertThat(perform(call, reader)).as("reader " + call.name()).isEqualTo(403);
            assertThat(perform(call, editor)).as("db admin " + call.name()).isNotIn(401, 403);
            assertThat(perform(call, superAdmin)).as("super admin " + call.name()).isNotIn(401, 403);
        }
        jdbc.update("UPDATE app_admins SET enabled = FALSE");
        adminAccess.evict();
        assertThat(perform(calls.get(0), editor)).as("disabled db admin").isEqualTo(403);
    }

    @Test
    void writesNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/api/manage/functions/ITS/groups").session(editor).contentType("application/json")
                .content("{\"name\":\"No token\"}")).andExpect(status().isForbidden());
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
}
