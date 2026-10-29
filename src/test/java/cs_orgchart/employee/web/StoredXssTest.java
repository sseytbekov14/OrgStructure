package cs_orgchart.employee.web;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stored XSS (Phase 5b): group, area of duties and job description are edited on the web and shown to every user
 * (and the CSP allows inline scripts). Audited on 2026-10-27: every place that renders them escapes them
 * (escapeHtml / highlightText) or uses textContent.
 * <ul>
 *   <li>the HTML sinks of each script are counted: a new innerHTML (or the like) fails here until it is reviewed
 *       and the count is updated;</li>
 *   <li>admin.js (the edit controls) has none at all: it builds everything with textContent / value;</li>
 *   <li>src/test/js/stored-xss.test.js renders a payload through every renderer of those texts; it runs here when
 *       node is installed (not in the CI image), otherwise by hand: {@code node src/test/js/stored-xss.test.js}.</li>
 * </ul>
 */
class StoredXssTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Pattern SINK = Pattern.compile("innerHTML|outerHTML|insertAdjacentHTML|document\\.write");

    @ParameterizedTest(name = "{0}: {1} reviewed HTML sinks")
    @CsvSource({"admin.js, 0", "common.js, 2", "function.js, 10", "main.js, 7", "org-stream.js, 0", "favicon.js, 0",
            "admin-page.js, 0", "ui.js, 0", "validation.js, 0", "admin-groups.js, 0", "admin-faq.js, 0",
            "photo-upload.js, 0"})
    void htmlSinksAreTheReviewedOnes(String file, long reviewed) throws IOException {
        String code = Files.readString(JS.resolve(file), StandardCharsets.UTF_8);

        assertThat(SINK.matcher(code).results().count())
                .as("%s: a new HTML sink must be reviewed for stored XSS (escapeHtml or textContent)", file)
                .isEqualTo(reviewed);
    }

    @Test
    void adminScriptPutsTextsOnlyThroughTextContentOrValue() throws IOException {
        String admin = Files.readString(JS.resolve("admin.js"), StandardCharsets.UTF_8);

        assertThat(admin).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write",
                "eval(", "new Function");
        assertThat(admin).contains("textContent");
    }

    @Test
    void everyRendererEscapesThePayload() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed: run node src/test/js/stored-xss.test.js");

        Process node = new ProcessBuilder("node", "src/test/js/stored-xss.test.js").redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains("stored XSS check passed").doesNotContain("FAIL");
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
