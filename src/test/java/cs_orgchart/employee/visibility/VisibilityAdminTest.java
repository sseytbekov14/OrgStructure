package cs_orgchart.employee.visibility;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.employee.EmployeeDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U4: hiding people. The mock hides Lima, Lilac (a manager): she is nowhere on the site and her counselees show under
 * the next visible manager; admins restore her; a hidden person cannot be a head; the AD sync never changes the flag.
 */
class VisibilityAdminTest extends DbModeMvcSupport {

    @Autowired
    private EmployeeDirectory directory;

    private List<cs_orgchart.model.Employee> rows(String name) {
        return directory.getAllEmployees().stream().filter(e -> name.equals(e.getName())).toList();
    }

    @Test
    void theHiddenManagerIsNowhereAndHerCounseleesHaveTheNextManager() throws Exception {
        assertThat(rows("Lima, Lilac")).isEmpty();
        assertThat(directory.search("Lilac")).isEmpty();
        cs_orgchart.model.Employee koi = rows("Kilo, Koi").get(0);
        assertThat(koi.getParentId()).as("Lilac's manager").isEqualTo(id("ggarnet"));
        assertThat(koi.getPm()).isNotEqualTo("Lima, Lilac");
        String all = mvc.perform(get("/api/employees").session(reader)).andReturn().getResponse().getContentAsString();
        assertThat(all).doesNotContain("Lilac");
    }

    @Test
    void adminsSeeTheHiddenListAndRestore() throws Exception {
        mvc.perform(get("/api/manage/hidden").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Lima, Lilac"))
                .andExpect(jsonPath("$[0].reason").exists());
        mvc.perform(write(delete("/api/manage/employees/" + id("llilac") + "/hide"), editor, null))
                .andExpect(status().isNoContent());
        assertThat(rows("Lima, Lilac")).isNotEmpty();
        assertThat(rows("Kilo, Koi").get(0).getParentId()).isEqualTo(id("llilac"));
        mvc.perform(write(delete("/api/manage/employees/" + id("llilac") + "/hide"), editor, null))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidingAHeadDropsThemFromTheHeadsWithAWarning() throws Exception {
        JsonNode result = body(mvc.perform(write(post("/api/manage/employees/" + id("kkhaki") + "/hide"), editor,
                        Map.of("reason", "On leave"))).andExpect(status().isOk()).andReturn());
        assertThat(result.get("warnings").get(0).asText()).isEqualTo("No longer a head of ITS.");
        mvc.perform(get("/api/manage/functions/ITS/heads").session(editor))
                .andExpect(jsonPath("$.heads[0].name").value("Lima, Lemon"))
                .andExpect(jsonPath("$.heads[0].rank").value(1))
                .andExpect(jsonPath("$.heads.length()").value(1));
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), editor, Map.of(
                        "employeeIds", List.of(id("llemon"), id("kkhaki")), "expectedHeads", List.of(id("llemon")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPLOYEE_HIDDEN"));
        mvc.perform(write(post("/api/manage/employees/" + id("kkhaki") + "/hide"), editor, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_HIDDEN"));
    }

    @Test
    void theReasonFollowsTheRules() throws Exception {
        mvc.perform(write(post("/api/manage/employees/" + id("mmint") + "/hide"), editor,
                        Map.of("reason", "<script>")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason.code").value("MARKUP"));
        mvc.perform(write(post("/api/manage/employees/" + id("mmint") + "/hide"), editor,
                        Map.of("reason", "x".repeat(201))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason.code").value("TOO_LONG"));
    }

    @Test
    void theAdSyncNeverChangesTheFlag() throws Exception {
        mvc.perform(write(post("/api/manage/employees/" + id("mmint") + "/hide"), editor, null))
                .andExpect(status().isOk());
        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_visibility WHERE hidden", Long.class))
                .isEqualTo(2);
        assertThat(rows("Mike, Mint")).isEmpty();
    }

    @Test
    void aHiddenPersonCannotBeMovedOrEdited() throws Exception {
        long pmo = body(mvc.perform(get("/api/manage/functions/People/groups").session(editor)).andReturn())
                .get("groups").get(0).get("id").asLong();
        mvc.perform(write(post("/api/manage/groups/" + pmo + "/members"), editor,
                        Map.of("employeeIds", List.of(id("llilac")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPLOYEE_HIDDEN"));
    }

    @Test
    void everyVisibilityEndpointIsForAdminsOnly() throws Exception {
        String hide = "/api/manage/employees/" + id("mmint") + "/hide";
        mvc.perform(get("/api/manage/hidden")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/manage/hidden").session(reader)).andExpect(status().isForbidden());
        mvc.perform(write(post(hide), reader, null)).andExpect(status().isForbidden());
        mvc.perform(write(delete(hide), reader, null)).andExpect(status().isForbidden());
        mvc.perform(post(hide).session(editor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/manage/hidden").session(superAdmin)).andExpect(status().isOk());
        assertThat(rows("Mike, Mint")).as("nothing hidden by the refused calls").isNotEmpty();
    }
}
