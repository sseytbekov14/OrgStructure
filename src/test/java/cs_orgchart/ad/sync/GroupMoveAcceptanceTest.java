package cs_orgchart.ad.sync;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.employee.DbEmployeeDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U4 acceptance scenario: person X is in ITS with the AD default group "IT Operations". An admin moves X to "IT PMO".
 * The Groups view lists X under IT PMO and not under IT Operations, the Tree card shows the chip IT PMO, an AD sync
 * changes nothing, and "Reset to AD data" brings X back to IT Operations. X is Mike, Mint with the AD department changed in
 * the fake directory (the shared mock fixture keeps its counts); the views are rendered with the page scripts through
 * node when it is installed.
 */
@Import(AdSyncTestSupport.FakeDirectoryConfig.class)
class GroupMoveAcceptanceTest extends DbModeMvcSupport {

    private static final String X = "Mike, Mint";

    @Autowired
    private FakeDirectory fakeDirectory;

    @Autowired
    private DbEmployeeDirectory dbDirectory;

    /** X: Mike, Mint with the AD department ITS / IT Operations and no overlay row (only the AD default group) */
    @BeforeEach
    void putXIntoItOperations() throws Exception {
        fakeDirectory.change("mmint", "department", "Central services/ITS/ITS General/IT Operations");
        fakeDirectory.change("mmint", "whenChanged", "20261028090000.0Z");
        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        jdbc.update("DELETE FROM employee_overrides WHERE employee_id = ?", id("mmint"));
        dbDirectory.rebuild();
    }
    @AfterEach
    void resetDirectory() {
        fakeDirectory.reset();
    }

    private JsonNode groups() throws Exception {
        return body(mvc.perform(get("/api/manage/functions/ITS/groups").session(editor)).andExpect(status().isOk())
                .andReturn());
    }

    private static JsonNode group(JsonNode groups, String name) {
        for (JsonNode g : groups.get("groups")) {
            if (g.get("name").asText().equals(name)) {
                return g;
            }
        }
        throw new AssertionError("no group " + name);
    }

    private static List<String> names(JsonNode group) {
        return group.get("members").findValuesAsText("name");
    }

    private String groupOfX() throws Exception {
        JsonNode rows = body(mvc.perform(get("/api/employees").param("cs", "ITS").session(reader)).andReturn());
        for (JsonNode row : rows) {
            if (X.equals(row.get("name").asText())) {
                return row.get("group").asText();
            }
        }
        throw new AssertionError(X + " not on the site");
    }

    @Test
    void movedToItPmoShowsInBothViewsSurvivesASyncAndResetsToTheAdGroup() throws Exception {
        assertThat(groupOfX()).isEqualTo("IT Operations");
        JsonNode before = groups();
        assertThat(names(group(before, "IT Operations"))).contains(X);
        assertThat(group(before, "IT Operations").get("source").asText()).isEqualTo("AUTO");
        assertThat(groupOfX()).isEqualTo("IT Operations");

        long pmo = group(before, "IT PMO").get("id").asLong();
        mvc.perform(write(post("/api/manage/groups/" + pmo + "/members"), editor,
                Map.of("employeeIds", List.of(id("mmint"))))).andExpect(status().isOk());

        JsonNode after = groups();
        assertThat(names(group(after, "IT PMO"))).contains(X);
        assertThat(names(group(after, "IT Operations"))).doesNotContain(X);
        assertThat(groupOfX()).isEqualTo("IT PMO");
        renderedIn("IT PMO");

        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(groupOfX()).as("the AD sync changes nothing").isEqualTo("IT PMO");
        assertThat(names(group(groups(), "IT PMO"))).contains(X);

        long version = jdbc.queryForObject("SELECT version FROM employee_overrides WHERE employee_id = ? AND cs = 'ITS'",
                Long.class, id("mmint"));
        mvc.perform(write(delete("/api/manage/overrides/" + id("mmint")).param("cs", "ITS")
                .param("version", String.valueOf(version)), editor, null)).andExpect(status().isNoContent());
        assertThat(groupOfX()).as("Reset to AD data").isEqualTo("IT Operations");
        assertThat(names(group(groups(), "IT Operations"))).contains(X);
        renderedIn("IT Operations");
    }

    /** The Groups view column and the Tree chip, rendered by function.js from the API answer */
    private void renderedIn(String group) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        String json = mvc.perform(get("/api/employees").param("cs", "ITS").session(reader)).andReturn().getResponse()
                .getContentAsString();
        Path file = Files.createTempFile("acceptance-its", ".json");
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
            Process node = new ProcessBuilder("node", "src/test/js/support/render-check.js", file.toString(), X, group)
                    .redirectErrorStream(true).start();
            String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(node.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(node.exitValue()).as(output).isZero();
            assertThat(output).contains("render check passed");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static boolean nodeAvailable() {
        try {
            Process version = new ProcessBuilder(List.of("node", "--version")).redirectErrorStream(true).start();
            version.getInputStream().readAllBytes();
            return version.waitFor(10, TimeUnit.SECONDS) && version.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
