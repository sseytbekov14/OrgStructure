package cs_orgchart.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OVERRIDES_IMPORT_FROM_EXCEL=true: the Excel import once at startup (spec v3 §13 Phase 5c, removed in Phase 8),
 * the report in OVERRIDES_IMPORT_REPORT_PATH and a summary in the log. Set the key back to false afterwards (a second
 * run changes nothing). Whatever happens, the application starts.
 */
@Slf4j
public class OverridesExcelImporter implements CommandLineRunner {

    private final OverlayImportService service;
    private final OverridesImportProperties properties;
    private final ObjectMapper json;

    @Value("${app.data.excel-path:}")
    private String excelPath;

    public OverridesExcelImporter(OverlayImportService service, OverridesImportProperties properties,
                                  ObjectMapper json) {
        this.service = service;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void run(String... args) {
        try {
            log.info("Excel import from {}{} ...", excelPath, properties.isDryRun() ? " (dry run)" : "");
            ImportReport report = service.run(new File(excelPath), properties.isDryRun(),
                    properties.getMaxManagerOverrideRatio());
            if ("DONE".equals(report.status) || "DRY_RUN".equals(report.status)) {
                log.info("{}", report.summary());
            } else {
                log.warn("{}", report.summary());
            }
            write(report);
            log.info("Set OVERRIDES_IMPORT_FROM_EXCEL=false again; to undo the import: DELETE FROM"
                    + " employee_overrides WHERE updated_by = '{}'", OverlayImportService.IMPORTED_BY);
        } catch (RuntimeException e) {
            log.error("Excel import could not run; the application goes on without it", e);
        }
    }

    private void write(ImportReport report) {
        Path path = Path.of(properties.getReportPath()).toAbsolutePath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), report);
            log.info("Excel import report written to {}", path);
        } catch (Exception e) {
            log.error("Excel import report could not be written to {}: {}", path, e.toString());
        }
    }
}
