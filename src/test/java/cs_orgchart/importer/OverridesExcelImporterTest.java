package cs_orgchart.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OVERRIDES_IMPORT_FROM_EXCEL=true: the import runs at startup, writes its report and never stops the application
 * (here a dry run on a database without synced employees: nothing to match, nothing written).
 */
@SpringBootTest(properties = {"OVERRIDES_IMPORT_FROM_EXCEL=true", "OVERRIDES_IMPORT_DRY_RUN=true"})
@ActiveProfiles("local")
class OverridesExcelImporterTest {

    private static final Path DIR = temp();
    private static final Path REPORT = DIR.resolve("reports/overrides-import-report.json");

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) throws IOException {
        Path excel = SyntheticExcel.fixture(DIR);
        registry.add("app.data.excel-path", excel::toString);
        registry.add("OVERRIDES_IMPORT_REPORT_PATH", REPORT::toString);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void runsAtStartupAndWritesTheReport() throws IOException {
        assertThat(context.getBeanNamesForType(OverridesExcelImporter.class)).hasSize(1);
        assertThat(REPORT).exists();

        JsonNode report = new ObjectMapper().readTree(Files.readString(REPORT));
        assertThat(report.get("dryRun").asBoolean()).isTrue();
        assertThat(report.get("status").asText()).isIn("NO_EMPLOYEES", "DRY_RUN");
        assertThat(Files.readString(REPORT)).doesNotContain("@");
    }

    private static Path temp() {
        try {
            return Files.createTempDirectory("orgchart-import-runner");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
