package cs_orgchart;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the node tests of the page scripts (src/test/js/*.test.js, no browser) when node is installed (not in the CI
 * image), otherwise by hand: {@code node src/test/js/<name>.test.js}. stored-xss.test.js runs in StoredXssTest.
 */
class JsTestsTest {

    private static final Path TESTS = Path.of("src/test/js");

    static Stream<String> scripts() throws IOException {
        try (Stream<Path> files = Files.list(TESTS)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".test.js") && !name.equals("stored-xss.test.js"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scripts")
    void scriptPasses(String script) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed: run node src/test/js/" + script);

        Process node = new ProcessBuilder("node", TESTS.resolve(script).toString()).redirectErrorStream(true).start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.waitFor(120, TimeUnit.SECONDS)).isTrue();

        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains(" passed").doesNotContain("FAIL");
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
