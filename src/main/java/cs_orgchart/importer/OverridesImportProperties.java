package cs_orgchart.importer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one-off import of group, heads and texts from the Excel file into employee_overrides (spec v3 §13 Phase 5c),
 * removed in Phase 8. The file is app.data.excel-path.
 */
@Data
@ConfigurationProperties("app.overrides-import")
public class OverridesImportProperties {

    /** OVERRIDES_IMPORT_FROM_EXCEL: run the import at startup */
    private boolean fromExcel = false;
    /** OVERRIDES_IMPORT_DRY_RUN: everything in memory, nothing written, the same report */
    private boolean dryRun = false;
    /** OVERRIDES_IMPORT_REPORT_PATH: the report (counts and Excel row numbers only) */
    private String reportPath = "logs/overrides-import-report.json";
    /**
     * OVERRIDES_IMPORT_MAX_MANAGER_OVERRIDE_RATIO: when more of the matched rows would need a manager override,
     * Excel PM and ORG_PARENT_SOURCE do not mean the same, and no override is created
     */
    private double maxManagerOverrideRatio = 0.10;
}
