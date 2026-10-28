package cs_orgchart.employee.web;

import cs_orgchart.access.DbModeMvcSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * U7 regression on the mock data (db mode, with the mock overlay: groups, a manual move, a hidden manager, two-function
 * person, heads): the Groups and the Tree view of every function, rendered by function.js through node from the real
 * /api/employees answer, show every person exactly once and the main head as the root of the tree.
 */
class EveryFunctionRendersTest extends DbModeMvcSupport {

    @Test
    void everyFunctionRendersInBothViews() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        String json = mvc.perform(get("/api/employees").session(reader)).andReturn().getResponse().getContentAsString();
        Path file = Files.createTempFile("every-function", ".json");
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
            Process node = new ProcessBuilder("node", "src/test/js/support/render-all.js", file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(node.waitFor(120, TimeUnit.SECONDS)).isTrue();
            assertThat(node.exitValue()).as(output).isZero();
            assertThat(output).contains("render all passed", "ok   ITS", "ok   People", "ok   F&O");
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
