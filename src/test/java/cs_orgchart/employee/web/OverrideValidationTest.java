package cs_orgchart.employee.web;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.access.DbModeMvcSupport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U3: the shared field rules on the overrides endpoint (400 with {errors: {field: {code, message}}}), the heads input,
 * isPrimaryFunction / adDefaultGroup of the members, and regression tests of the bugs found in the edit flow.
 */
class OverrideValidationTest extends DbModeMvcSupport {

    private long version(String login, String cs) {
        return jdbc.queryForObject("SELECT version FROM employee_overrides WHERE employee_id = ? AND cs = ?",
                Long.class, id(login), cs);
    }

    private Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    /** A group made in Manage groups (the edit only chooses existing groups) */
    private void createGroup(String cs, String name) throws Exception {
        mvc.perform(write(post("/api/manage/functions/" + cs + "/groups"), editor, Map.of("name", name)))
                .andExpect(status().isCreated());
    }

    private long groupCount(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM function_groups WHERE lower(name) = lower(?)", Long.class,
                name);
    }

    // --- the group is one of the function's groups ------------------------------------------------------------

    @Test
    void aGroupThatDoesNotExistIsRefusedAndNoGroupIsMade() throws Exception {
        long version = version("mmint", "ITS");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", "Brand new team", "version", version)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.groupName.code").value("UNKNOWN_GROUP"))
                .andExpect(jsonPath("$.errors.groupName.message")
                        .value("Choose one of the groups of ITS. New groups are created in Manage groups."));
        assertThat(groupCount("Brand new team")).as("no group made by the edit").isZero();
        assertThat(version("mmint", "ITS")).as("nothing stored").isEqualTo(version);
    }

    @Test
    void aGroupOfAnotherFunctionIsRefused() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM function_groups WHERE cs = 'ITS' AND name = 'IT PMO'",
                Long.class)).as("IT PMO is a group of ITS").isOne();
        mvc.perform(write(put("/api/manage/overrides/" + id("nnavy")).param("cs", "QRM"), editor,
                        body("groupName", "IT PMO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.groupName.code").value("UNKNOWN_GROUP"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_overrides WHERE employee_id = ? AND cs = 'QRM'",
                Long.class, id("nnavy"))).as("no row made").isZero();
    }

    @Test
    void anExistingGroupIsChosenByItsListedNameAndEmptyMeansTheGroupFromAd() throws Exception {
        createGroup("ITS", "Platform team");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", " platform TEAM ", "version", version("mmint", "ITS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("Platform team"));
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", "", "version", version("mmint", "ITS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT group_id FROM employee_overrides WHERE employee_id = ? AND cs = 'ITS'",
                Long.class, id("mmint"))).as("back to the group from AD").isNull();
    }

    /** A group whose name breaks the text rules (e.g. from the Excel import or AD) can still be chosen */
    @Test
    void aListedGroupIsAcceptedEvenWhenItsNameBreaksTheTextRules() throws Exception {
        jdbc.update("INSERT INTO function_groups(cs, name, kind, source, sort_order, active, version, created_by,"
                + " created_at, updated_by, updated_at) VALUES ('ITS', 'R&D  [legacy]', 'TEAM', 'MANUAL', 999, TRUE,"
                + " 0, 'test', now(), 'test', now())");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", "R&D  [legacy]", "version", version("mmint", "ITS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("R&D  [legacy]"));
    }

    @Test
    void everyRefusedFieldIsAnsweredWithItsCodeAndMessage() throws Exception {
        long version = version("mmint", "ITS");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", "x", "areaOfDuties", "<b>bold</b>", "jobDescription", "=SUM(A1)",
                                "version", version)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.errors.groupName.code").value("TOO_SHORT"))
                .andExpect(jsonPath("$.errors.groupName.message").value("Group needs at least 2 characters."))
                .andExpect(jsonPath("$.errors.areaOfDuties.code").value("MARKUP"))
                .andExpect(jsonPath("$.errors.jobDescription.code").value("FORMULA"));
        assertThat(version("mmint", "ITS")).as("nothing stored").isEqualTo(version);
    }

    @Test
    void eachRejectionClassIsRefusedByTheEndpoint() throws Exception {
        long version = version("mmint", "ITS");
        Map<String, String> bad = Map.of(
                "Pay\u200Broll", "INVISIBLE", "\u202Eevil", "INVISIBLE", "Bell\u0007", "CONTROL",
                "see javascript:alert(1)", "SCRIPT", "@team", "FORMULA", "Team $", "CHARACTERS",
                "x".repeat(121), "TOO_LONG");
        for (Map.Entry<String, String> entry : bad.entrySet()) {
            mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                            body("areaOfDuties", entry.getKey(), "version", version)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.areaOfDuties.code").value(entry.getValue()));
        }
    }

    @Test
    void valuesAreStoredNormalized() throws Exception {
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", "  Service\u00A0\u00A0Desk ", "areaOfDuties", "Café\tIT",
                                "jobDescription", "  ", "version", version("mmint", "ITS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("Service Desk"))
                .andExpect(jsonPath("$.areaOfDuties").value("Café IT"))
                .andExpect(jsonPath("$.jobDescription").doesNotExist());
    }

    /** Regression: a group named HEAD made a non-head look like a head of the function in the Groups view */
    @Test
    void headIsNotAGroupName() throws Exception {
        for (String head : List.of("HEAD", "head", " Head ")) {
            mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                            body("groupName", head, "version", version("mmint", "ITS"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.groupName.code").value("RESERVED"));
        }
    }

    /** Values from before the U3 rules (e.g. the Excel import) are kept when sent back unchanged */
    @Test
    void anUntouchedOldValueDoesNotBlockTheEditOfAnotherField() throws Exception {
        String legacy = "x".repeat(300) + "  <old>";
        jdbc.update("UPDATE employee_overrides SET job_description = ? WHERE employee_id = ? AND cs = 'ITS'",
                legacy, id("mmint"));
        createGroup("ITS", "Workplace");
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("groupName", "Workplace", "jobDescription", legacy, "version", version("mmint", "ITS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("Workplace"))
                .andExpect(jsonPath("$.jobDescription").value(legacy));
        mvc.perform(write(put("/api/manage/overrides/" + id("mmint")).param("cs", "ITS"), editor,
                        body("jobDescription", legacy + "!", "version", version("mmint", "ITS"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.jobDescription.code").value("MARKUP"));
    }

    @Test
    void theHeadsInputIsLimited() throws Exception {
        List<Long> many = LongStream.rangeClosed(1, 21).boxed().toList();
        mvc.perform(write(put("/api/manage/functions/ITS/heads"), editor,
                        Map.of("employeeIds", many, "expectedHeads", List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.employeeIds.code").value("TOO_MANY"));
    }

    @Test
    void membersTellTheirOwnFunctionAndTheAdDefaultGroup() throws Exception {
        JsonNode its = body(mvc.perform(get("/api/manage/overrides").param("cs", "ITS").param("q", "navy")
                .session(editor)).andExpect(status().isOk()).andReturn());
        assertThat(its).hasSize(1);
        assertThat(its.get(0).get("isPrimaryFunction").asBoolean()).isTrue();
        assertThat(its.get(0).get("adDefaultGroup").asText()).isEqualTo("IT PMO");

        JsonNode fo = body(mvc.perform(get("/api/manage/overrides").param("cs", "F&O").param("q", "honey")
                .session(editor)).andExpect(status().isOk()).andReturn());
        assertThat(fo).hasSize(1);
        assertThat(fo.get(0).get("isPrimaryFunction").asBoolean()).as("F&O is an extra function").isFalse();
        assertThat(fo.get(0).get("adDefaultGroup").isNull()).isTrue();

        JsonNode people = body(mvc.perform(get("/api/manage/overrides").param("cs", "People").param("q", "honey")
                .session(editor)).andExpect(status().isOk()).andReturn());
        assertThat(people.get(0).get("isPrimaryFunction").asBoolean()).as("People is the AD function").isTrue();
    }

    @Test
    void theHeadsAnswerCarriesTheCountryCode() throws Exception {
        mvc.perform(get("/api/manage/functions/ITS/heads").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heads[0].countryCode").value("KZ"));
    }
}
