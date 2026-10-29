package cs_orgchart.employee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.FakeDirectory;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.model.Employee;
import cs_orgchart.service.ExcelService;
import cs_orgchart.service.OrgStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EMPLOYEE_SOURCE=db on PostgreSQL with the mock directory: the site's employees come from the database, the Excel
 * file is not needed, the directory follows the syncs and the photos, and the API keeps its format.
 */
@SpringBootTest(properties = {"EMPLOYEE_SOURCE=db", "AD_SYNC_MODE=mock", "AD_SYNC_ENABLED=false",
        "app.data.watch-interval-ms=200"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(DbEmployeeDirectoryTest.FakeDirectoryConfig.class)
class DbEmployeeDirectoryTest {

    /** The fields of /api/employees before Phase 5 (Excel) */
    static final Set<String> PREVIOUS_FIELDS = Set.of("name", "cs", "group", "jobTitle", "email", "pm", "pmEmail",
            "pmJobTitle", "photoUrl", "gradeOrder", "areaOfDuties", "jobDescription");
    static final Set<String> NEW_FIELDS = Set.of("id", "parentId", "countryCode", "groupOrder", "city", "country",
            "pmId", "pmExternal", "pmPhotoUrl", "photoUploaded");
    static final Set<String> SITE_FUNCTIONS = Set.of("People", "Finance", "ITS", "Markets", "F&O", "OGC", "QRM",
            "Tenders");

    private static final Path ROOT = temp();
    private static final Path PHOTOS = ROOT.resolve("photos");
    private static final Path MISSING_EXCEL = ROOT.resolve("no-such-dir").resolve("result_new.xlsx");

    @TestConfiguration
    static class FakeDirectoryConfig {
        @Bean
        @Primary
        FakeDirectory fakeDirectory() {
            return new FakeDirectory();
        }
    }

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) {
        registry.add("app.data.excel-path", MISSING_EXCEL::toString);
        registry.add("app.data.photos-path", PHOTOS::toString);
    }

    @Autowired
    private AdSyncService sync;

    @Autowired
    private FakeDirectory fake;

    @Autowired
    private DbEmployeeDirectory dbDirectory;

    @Autowired
    private EmployeeDirectory directory;

    @Autowired
    private ExcelService excelService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @MockitoSpyBean
    private OrgStreamService orgStream;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clean() throws IOException {
        fake.reset();
        jdbc.update("DELETE FROM employee_overrides");
        jdbc.update("DELETE FROM employees");
        jdbc.update("DELETE FROM ad_sync_log");
        try (var files = Files.list(PHOTOS)) {
            for (Path photo : files.toList()) {
                Files.delete(photo);
            }
        }
        clearInvocations(orgStream);
    }

    private void syncMock() {
        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
    }

    private List<Employee> rowsOf(String nameStart) {
        return directory.getAllEmployees().stream().filter(e -> e.getName().startsWith(nameStart)).toList();
    }

    // --- start without Excel, empty database ---------------------------------------------------------------------

    @Test
    void startsWithTheExcelFileAndItsDirectoryMissingAndNeverReadsIt() {
        assertThat(Files.exists(MISSING_EXCEL.getParent())).isFalse();
        syncMock();
        int rows = directory.getAllEmployees().size();

        excelService.reloadData();

        assertThat(directory.getAllEmployees()).hasSize(rows).allSatisfy(e -> assertThat(e.getId()).isNotNull());
    }

    @Test
    void emptyDatabaseGivesAnEmptyDirectoryAndAWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(DbEmployeeDirectory.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            assertThat(dbDirectory.load()).isZero();
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(directory.getAllEmployees()).isEmpty();
        assertThat(logged.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("EMPLOYEE_SOURCE=db", "EMPLOYEE_SOURCE=excel");
        });
    }

    // --- rebuild after a sync ------------------------------------------------------------------------------------

    @Test
    void syncRebuildsTheDirectoryWithTheSeededOverlayAndPublishesOrgUpdated() {
        syncMock();

        verify(orgStream).publishOrgUpdated("ad-sync");
        assertThat(directory.getFunctions()).containsExactlyInAnyOrderElementsOf(SITE_FUNCTIONS);
        // The mock overlay is seeded before the rebuild: the person in two functions is in both
        assertThat(rowsOf("Hotel, Honey")).extracting(Employee::getCs).containsExactlyInAnyOrder("People", "F&O");
        assertThat(rowsOf("Hotel, Honey")).extracting(Employee::getGroup)
                .containsExactlyInAnyOrder("HR Administration", "Office support");
        // Heads first in their function, by head_rank
        List<Employee> its = directory.getAllEmployees().stream().filter(e -> "ITS".equals(e.getCs())).toList();
        assertThat(its.subList(0, 2)).extracting(Employee::getName).containsExactly("Kilo, Khaki", "Lima, Lemon");
        assertThat(its.subList(0, 2)).extracting(Employee::getGroup).containsOnly("HEAD");
        assertThat(its.get(2).getGroup()).isNotEqualTo("HEAD");
    }

    @Test
    void onlyEnabledCentralServicesEmployeesAreListedAndExternalParentsOnlyAsPm() {
        syncMock();

        assertThat(directory.getAllEmployees()).extracting(Employee::getName)
                .doesNotContain("Romeo, Rust", "Papa, Pearl", "Quebec, Quince", "Tango, Dev");
        Employee head = rowsOf("Alfa, Amber").get(0);
        assertThat(head.getPm()).isEqualTo("Romeo, Rust");
        assertThat(head.getPmEmail()).isEqualTo("romeo.rust@kz.example");
        assertThat(head.getParentId()).isNull();
        Employee member = rowsOf("Bravo, Blue").get(0);
        assertThat(member.getParentId()).isEqualTo(head.getId());
        assertThat(rowsOf("Kilo, Khaki").get(0).getPm()).as("no manager, no PM").isNull();

        fake.remove("mmauve");
        syncMock();
        assertThat(rowsOf("Mike, Mauve")).as("leaver gone after the next sync").isEmpty();
    }

    @Test
    void rebuildPublishesOrgUpdatedForAdminEdits() {
        syncMock();
        clearInvocations(orgStream);
        jdbc.update("UPDATE employee_overrides SET area_of_duties = 'Edited' WHERE id = (SELECT min(id) FROM"
                + " employee_overrides)");

        dbDirectory.rebuild();

        verify(orgStream).publishOrgUpdated("employee-directory");
        assertThat(directory.getAllEmployees()).anyMatch(e -> "Edited".equals(e.getAreaOfDuties()));
    }

    // --- API -----------------------------------------------------------------------------------------------------

    @Test
    void apiKeepsThePreviousFormatAddsIdParentIdCountryCodeAndNeverPhoneMobileOrOffice() throws Exception {
        syncMock();

        for (String url : List.of("/api/employees", "/api/employees?cs=ITS", "/api/search?q=manager")) {
            String body = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            List<Map<String, Object>> rows = json.readValue(body, new TypeReference<>() {
            });
            assertThat(rows).as(url).isNotEmpty().allSatisfy(row -> {
                assertThat(row.keySet()).containsAll(PREVIOUS_FIELDS);
                assertThat(row.keySet()).isSubsetOf(union());
                assertThat(row.get("gradeOrder")).isInstanceOf(Integer.class);
                assertThat(row.get("id")).isInstanceOf(Number.class);
                assertThat(row.get("countryCode")).isIn("KZ", "UZ", "AZ", "GE", "AM", "KG");
            });
            assertThat(body).as(url).doesNotContain("\"phone\"", "\"mobile\"", "\"office\"", "+000 ", "Almaty Office");
        }
    }

    @Test
    void functionsAndCountryFilter() throws Exception {
        syncMock();

        List<String> functions = json.readValue(mvc.perform(get("/api/functions")).andReturn().getResponse()
                .getContentAsString(), new TypeReference<>() {
        });
        assertThat(functions).containsExactlyInAnyOrderElementsOf(SITE_FUNCTIONS);

        List<Map<String, Object>> uz = json.readValue(mvc.perform(get("/api/employees?country=uz")).andReturn()
                .getResponse().getContentAsString(), new TypeReference<>() {
        });
        assertThat(uz).isNotEmpty().allSatisfy(row -> assertThat(row.get("countryCode")).isEqualTo("UZ"));

        List<Map<String, Object>> found = json.readValue(mvc.perform(get("/api/search?q=accountant&cs=Finance"
                + "&country=GE")).andReturn().getResponse().getContentAsString(), new TypeReference<>() {
        });
        assertThat(found).extracting(row -> row.get("name")).containsExactly("Alfa, Azure");
    }

    // --- photos --------------------------------------------------------------------------------------------------

    @Test
    void aNewPhotoRebuildsTheDirectory() throws Exception {
        syncMock();
        assertThat(rowsOf("Mike, Mint").get(0).getPhotoUrl()).isEqualTo("/photos/default.jpg");

        Files.writeString(PHOTOS.resolve("mike.mint@kz.example.jpg"), "image");

        await(() -> "/photos/mike.mint@kz.example.jpg".equals(rowsOf("Mike, Mint").get(0).getPhotoUrl()));
        verify(orgStream, atLeastOnce()).publishOrgUpdated(anyString());
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private static Set<String> union() {
        Set<String> all = new HashSet<>(PREVIOUS_FIELDS);
        all.addAll(NEW_FIELDS);
        return all;
    }

    private static Path temp() {
        try {
            Path root = Files.createTempDirectory("orgchart-db-directory");
            Files.createDirectories(root.resolve("photos"));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached within 20 s");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
