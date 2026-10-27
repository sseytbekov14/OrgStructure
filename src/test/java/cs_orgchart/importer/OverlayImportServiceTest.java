package cs_orgchart.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.override.OverrideAdminService;
import cs_orgchart.employee.override.OverrideAdminViews.OverrideRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Excel → overlay import on PostgreSQL, after a mock AD sync (synthetic people), with the synthetic fixture of
 * {@link SyntheticExcel}. The overlay starts empty (the seeded mock overlay is removed) unless a test needs it.
 */
@SpringBootTest(properties = {"EMPLOYEE_SOURCE=db", "AD_SYNC_MODE=mock", "AD_SYNC_ENABLED=false"})
@ActiveProfiles("local")
class OverlayImportServiceTest {

    @Autowired
    private OverlayImportService importer;

    @Autowired
    private AdSyncService sync;

    @Autowired
    private OverrideAdminService admin;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private EmployeeOverrideRepository overlay;

    @Autowired
    private EmployeeDirectory directory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    @TempDir
    Path dir;

    private File excel;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void syncAndWriteFixture() throws Exception {
        clean();
        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        jdbc.update("DELETE FROM employee_overrides");
        jdbc.update("UPDATE employees SET enabled = false WHERE login = 'nnickel'");
        excel = SyntheticExcel.fixture(dir).toFile();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM employee_overrides");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM ad_sync_log");
    }

    private ImportReport run() {
        return importer.run(excel, false, 0.10);
    }

    // --- the import ----------------------------------------------------------------------------------------------

    @Test
    void importsTheMatchedRowsByTheRules() {
        ImportReport report = run();

        assertThat(report.status).isEqualTo("DONE");
        assertThat(report.excelRows).isEqualTo(16);
        assertThat(report.emptyRows).isEqualTo(1);
        assertThat(report.matchedRows).isEqualTo(15);
        assertThat(report.matchedByEmail).as("rows 3 (other case) and 11 (NBSP) included").isEqualTo(14);
        assertThat(report.matchedByUpn).isZero();
        assertThat(report.matchedByLocalPart).isEqualTo(1);
        assertThat(report.localPartRows).containsExactly(4);
        assertThat(report.unmatchedRows).containsExactly(7);
        assertThat(report.disabledEmployeeRows).containsExactly(8);
        assertThat(report.unknownCsRows).containsExactly(9);
        assertThat(report.unknownCsValues).containsExactly("Sales");
        assertThat(report.externalEmployeeRows).containsExactly(15);
        assertThat(report.rowsWithoutCs).containsExactly(17);
        assertThat(report.duplicateRows).containsExactly(18);
        assertThat(report.rowsCreated).isEqualTo(10);
        assertThat(report.rowsUnchanged).as("the duplicate fills nothing more").isEqualTo(1);
        assertThat(report.headsSet).containsExactly(Map.entry("ITS", 2));
        assertThat(report.titleSuffixMismatchRows).containsExactly(12);
        assertThat(report.pmUnresolvedRows).containsExactly(14);
        assertThat(report.managerOverrides.candidates).isEqualTo(1);
        assertThat(report.managerOverrides.created).isEqualTo(1);
        assertThat(report.managerOverrides.refused).isFalse();
        assertThat(report.defaultGroups.rowsWithGroup).isEqualTo(5);
        assertThat(report.defaultGroups.equalToDefaultGroup).isEqualTo(2);
        assertThat(report.defaultGroups.percent).isEqualTo(40.0);
        assertThat(report.adEmployeesNotInExcelByFunction).isNotEmpty().doesNotContainKey("(no function)");

        EmployeeOverride khaki = row("kkhaki", "ITS");
        assertThat(khaki.getHeadRank()).isEqualTo((short) 1);
        assertThat(khaki.getGroupName()).as("a head has no group").isNull();
        assertThat(khaki.getAreaOfDuties()).isEqualTo("IT strategy");
        assertThat(khaki.getJobDescription()).as("the duplicate never overwrites").isEqualTo("Leads IT");
        assertThat(row("llemon", "ITS").getHeadRank()).isEqualTo((short) 2);
        assertThat(row("mmint", "ITS").getGroupName()).isEqualTo("Service Desk");
        assertThat(row("hhoney", "People").getManagerOverrideId()).as("Excel PM = parent in AD").isNull();
        assertThat(row("hhoney", "F&O").getManagerOverrideId()).isEqualTo(id("ccoral"));
        assertThat(row("aazure", "Finance").getManagerOverrideId()).as("PM of another domain = parent in AD").isNull();
        EmployeeOverride olive = row("oolive", "ITS");
        assertThat(olive.getGroupName()).as("NBSP only -> empty").isNull();
        assertThat(olive.getAreaOfDuties()).isNull();
        assertThat(olive.getJobDescription()).isEqualTo("Field support");
        assertThat(overlay.findAll()).hasSize(10)
                .allSatisfy(o -> assertThat(o.getUpdatedBy()).isEqualTo(OverlayImportService.IMPORTED_BY));
    }

    @Test
    void refusedValuesAreReportedAndTheRestOfTheRowIsImported() {
        ImportReport report = run();

        assertThat(report.rejectedValues).containsExactlyInAnyOrder(
                new ImportReport.Rejected(10, "Area  of Duties", "HTML"),
                new ImportReport.Rejected(10, "Job Description", "TOO_LONG"));
        EmployeeOverride zinc = row("zzinc", "Finance");
        assertThat(zinc.getGroupName()).isEqualTo("Accounting General");
        assertThat(zinc.getAreaOfDuties()).isNull();
        assertThat(zinc.getJobDescription()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_overrides WHERE area_of_duties LIKE '%<%'"
                + " OR length(job_description) > 500", Long.class)).isZero();
    }

    @Test
    void disabledEmployeesAndUnknownFunctionsAreSkipped() {
        run();

        assertThat(overlay.findAll()).extracting(EmployeeOverride::getEmployeeId)
                .doesNotContain(id("nnickel"), id("nnavy"), id("rrust"), id("tteal"));
        assertThat(overlay.findAll()).extracting(EmployeeOverride::getCs).doesNotContain("Sales");
    }

    @Test
    void theDirectoryIsRebuiltAfterTheImport() {
        run();

        assertThat(directory.getAllEmployees()).filteredOn(e -> e.getName().equals("Mike, Mint"))
                .singleElement().satisfies(e -> assertThat(e.getGroup()).isEqualTo("Service Desk"));
        assertThat(directory.getAllEmployees()).filteredOn(e -> e.getName().equals("Hotel, Honey"))
                .extracting(cs_orgchart.model.Employee::getCs).containsExactlyInAnyOrder("People", "F&O");
    }

    // --- safety --------------------------------------------------------------------------------------------------

    @Test
    void dryRunWritesNothingAndReportsTheSame() throws Exception {
        Map<Long, String> before = overlaySnapshot();

        ImportReport dry = importer.run(excel, true, 0.10);

        assertThat(dry.status).isEqualTo("DRY_RUN");
        assertThat(dry.dryRun).isTrue();
        assertThat(overlaySnapshot()).isEqualTo(before).isEmpty();

        ImportReport real = run();
        assertThat(comparable(dry)).isEqualTo(comparable(real));
    }

    @Test
    void nothingHappensWithoutSyncedEmployees() {
        jdbc.update("DELETE FROM employees");

        ImportReport report = run();

        assertThat(report.status).isEqualTo("NO_EMPLOYEES");
        assertThat(report.message).contains("run an AD sync first");
        assertThat(overlay.count()).isZero();
    }

    @Test
    void tooManyManagerOverridesCreateNone() {
        ImportReport report = importer.run(excel, false, 0.05);

        assertThat(report.status).isEqualTo("DONE");
        assertThat(report.managerOverrides.refused).isTrue();
        assertThat(report.managerOverrides.created).isZero();
        assertThat(report.managerOverrides.message).startsWith("PM semantics mismatch: decide ORG_PARENT_SOURCE first");
        assertThat(overlay.findAll()).hasSize(10).allSatisfy(o -> assertThat(o.getManagerOverrideId()).isNull());
    }

    @Test
    void aRunningSyncBlocksTheImport() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement lock = other.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(" + AdSyncService.LOCK_KEY + ")");
            }

            ImportReport report = run();

            assertThat(report.status).isEqualTo("LOCKED");
            assertThat(report.message).contains("nothing was imported");
            assertThat(overlay.count()).isZero();
            other.rollback();
        }
    }

    @Test
    void missingSheetColumnOrFileFailsAndChangesNothing() throws Exception {
        File noCsColumn = SyntheticExcel.write(dir.resolve("no-cs.xlsx"), SyntheticExcel.headersWithout("CS"),
                SyntheticExcel.withoutColumn(SyntheticExcel.fixture(), 2)).toFile();

        ImportReport missingColumn = importer.run(noCsColumn, false, 0.10);
        ImportReport missingFile = importer.run(dir.resolve("none.xlsx").toFile(), false, 0.10);

        assertThat(missingColumn.status).isEqualTo("FAILED");
        assertThat(missingColumn.message).contains("no column 'cs'", "header names only");
        assertThat(missingFile.status).isEqualTo("FAILED");
        assertThat(missingFile.message).contains("does not exist");
        assertThat(overlay.count()).isZero();
    }

    @Test
    void theExcelFileIsNeverWritten() throws Exception {
        String before = sha256(excel.toPath());
        long modified = excel.lastModified();

        run();
        importer.run(excel, true, 0.10);

        assertThat(sha256(excel.toPath())).isEqualTo(before);
        assertThat(excel.lastModified()).isEqualTo(modified);
    }

    @Test
    void anAmbiguousLocalPartIsNoMatch() {
        Employee other = employees.save(Employee.builder().adGuid(UUID.randomUUID())
                .dn("CN=mmint2,OU=Almaty,OU=Users,OU=KZ,OU=Countries,OU=CCG,DC=corp,DC=example").login("mmint2")
                .upn("mmint2@kz.example").email("mike.mint@other.example").displayName("Mike, Mint Second")
                .countryCode("KZ").build());

        ImportReport report = run();

        assertThat(report.unmatchedRows).containsExactly(4, 7);
        assertThat(report.ambiguousLocalPartRows).containsExactly(4);
        assertThat(overlay.findByEmployeeIdAndCs(id("mmint"), "ITS")).isEmpty();
        assertThat(overlay.findByEmployeeIdAndCs(other.getId(), "ITS")).isEmpty();
    }

    // --- idempotency and undo ------------------------------------------------------------------------------------

    @Test
    void aSecondRunChangesNothingAndNeverOverwritesAdminEdits() {
        run();
        EmployeeOverride mint = row("mmint", "ITS");
        admin.upsert(id("mmint"), "ITS", new OverrideRequest("Service Desk", "Edited by an admin", null,
                mint.getVersion()), "admin@corp.example");
        Map<Long, String> before = overlaySnapshot();

        ImportReport second = run();

        assertThat(second.rowsCreated).isZero();
        assertThat(second.rowsFilled).isZero();
        assertThat(second.rowsEditedByOthers).containsExactly(4);
        assertThat(overlaySnapshot()).isEqualTo(before);
        assertThat(row("mmint", "ITS").getAreaOfDuties()).isEqualTo("Edited by an admin");
        assertThat(row("mmint", "ITS").getJobDescription()).as("an admin row is not filled either").isNull();
    }

    @Test
    void functionsThatHaveHeadsKeepThem() {
        jdbc.update("INSERT INTO employee_overrides (employee_id, cs, head_rank, updated_by) VALUES (?, 'ITS', 1,"
                + " 'admin@corp.example')", id("nnavy"));

        ImportReport report = run();

        assertThat(report.headsSkippedFunctions).containsExactly("ITS");
        assertThat(report.headsSet).isEmpty();
        assertThat(row("kkhaki", "ITS").getHeadRank()).isNull();
        assertThat(row("llemon", "ITS").getHeadRank()).isNull();
        assertThat(row("nnavy", "ITS").getHeadRank()).isEqualTo((short) 1);
    }

    @Test
    void theUndoStatementRemovesOnlyImportedRows() {
        run();
        EmployeeOverride zinc = row("zzinc", "Finance");
        admin.upsert(id("zzinc"), "Finance", new OverrideRequest("Accounting General", "Edited", null,
                zinc.getVersion()), "admin@corp.example");
        admin.upsert(id("nnavy"), "QRM", new OverrideRequest("Reviews", null, null, null), "admin@corp.example");

        int removed = jdbc.update("DELETE FROM employee_overrides WHERE updated_by = 'excel-import'");

        assertThat(removed).isEqualTo(9);
        assertThat(overlay.findAll()).extracting(o -> o.getEmployeeId() + "/" + o.getCs())
                .containsExactlyInAnyOrder(id("zzinc") + "/Finance", id("nnavy") + "/QRM");
    }

    // --- report --------------------------------------------------------------------------------------------------

    @Test
    void theReportHasRowNumbersAndCountsButNoNamesOrEmails() throws Exception {
        String text = json.writeValueAsString(run());

        assertThat(text).doesNotContain("@", "kz.example", "kpmg.com", "Khaki", "Lemon", "Honey", "Person", "Ledger",
                "IT strategy", "Leads IT", "Accountant");
        JsonNode report = json.readTree(text);
        assertThat(report.get("unmatchedRows").get(0).asInt()).isEqualTo(7);
        assertThat(report.get("rejectedValues").get(0).get("row").asInt()).isEqualTo(10);
    }

    @Test
    void theStartupImportIsOffUnlessEnabled() {
        assertThat(context.getBeanNamesForType(OverridesExcelImporter.class)).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private long id(String login) {
        return employees.findAll().stream().filter(e -> e.getLogin().equals(login)).findFirst()
                .map(Employee::getId).orElseThrow();
    }

    private EmployeeOverride row(String login, String cs) {
        return overlay.findByEmployeeIdAndCs(id(login), cs).orElseThrow();
    }

    private Map<Long, String> overlaySnapshot() {
        Map<Long, String> rows = new HashMap<>();
        jdbc.query("SELECT * FROM employee_overrides ORDER BY id", (ResultSet rs) -> {
            List<Object> values = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                values.add(rs.getObject(i));
            }
            rows.put(rs.getLong("id"), values.toString());
        });
        return rows;
    }

    /** The report without the fields that differ between a dry and a real run */
    private JsonNode comparable(ImportReport report) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = json.valueToTree(report);
        node.remove(List.of("status", "dryRun", "startedAt"));
        return node;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
