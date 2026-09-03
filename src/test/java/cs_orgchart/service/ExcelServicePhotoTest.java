package cs_orgchart.service;

import cs_orgchart.model.Employee;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for {@link ExcelService}: verifies that employees are correctly
 * loaded from Excel, photos are resolved by email, and the in-memory cache remains
 * stable across multiple reloadData() invocations (FileWatcher debounce simulation).
 *
 * <p>Uses the "local" Spring profile so that SecurityConfig activates its local
 * branch (permitAll, no OAuth2). ClientRegistrationRepository is mocked because
 * the OAuth2 starter is on the classpath and Spring Security needs the bean
 * even when it is unused in the local security chain.
 */
@SpringBootTest
@ActiveProfiles("local")
class ExcelServicePhotoTest {

    @Autowired
    private ExcelService excelService;

    @MockitoBean
    ClientRegistrationRepository clientRegistrationRepository;

    private static Path tempDir;
    private static Path excelFile;
    private static Path photosDir;

    /**
     * Registers temp-directory paths BEFORE the Spring context starts,
     * so that ExcelService's @PostConstruct can find the file.
     * The actual file is created in @BeforeEach; @PostConstruct will log a
     * FileNotFoundException (gracefully handled) and the test calls reloadData() explicitly.
     */
    @DynamicPropertySource
    static void overrideFilePaths(DynamicPropertyRegistry registry) throws IOException {
        tempDir   = Files.createTempDirectory("orgchart-test");
        excelFile = tempDir.resolve("test_data.xlsx");
        photosDir = tempDir.resolve("photos");
        Files.createDirectories(photosDir);

        registry.add("app.data.excel-path",   () -> excelFile.toAbsolutePath().toString());
        registry.add("app.data.photos-path",   () -> photosDir.toAbsolutePath().toString());
        // Disable file watcher polling in tests to avoid thread interference
        registry.add("app.data.watch-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void setUp() throws IOException {
        /*
         * Column layout must match ExcelService column-name lookup:
         *   colMap keys (lower-cased): "name", "cs", "group", "job title", "e-mail" / "email"
         * ExcelService falls back to positional indexes only when header not found,
         * so we must use the exact header strings it recognises.
         */
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("NEW");

            // Header row — names must match ExcelService.readEmployeesFromExcel() colMap keys
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("cs");
            header.createCell(2).setCellValue("group");
            header.createCell(3).setCellValue("job title");
            header.createCell(4).setCellValue("e-mail");   // ExcelService looks for "e-mail" first

            // Employee 1 — has a photo
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("John Doe");
            row1.createCell(1).setCellValue("IT");
            row1.createCell(2).setCellValue("Backend");
            row1.createCell(3).setCellValue("Developer");
            row1.createCell(4).setCellValue("john.doe@example.com");

            // Employee 2 — no photo, expects default
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Jane Smith");
            row2.createCell(1).setCellValue("HR");
            row2.createCell(2).setCellValue("Recruitment");
            row2.createCell(3).setCellValue("HR Manager");
            row2.createCell(4).setCellValue("jane.smith@example.com");

            try (FileOutputStream fos = new FileOutputStream(excelFile.toFile())) {
                workbook.write(fos);
            }
        }

        // Create a photo only for John Doe (matched by full email filename)
        Files.writeString(photosDir.resolve("john.doe@example.com.jpg"), "dummy-image-bytes");

        // Trigger explicit reload so ExcelService picks up the freshly created file
        excelService.reloadData();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(photosDir.resolve("john.doe@example.com.jpg"));
        Files.deleteIfExists(excelFile);
    }

    /**
     * Verifies:
     * 1. Both employees are loaded correctly from the temp Excel file.
     * 2. John Doe's photo URL resolves to the existing file.
     * 3. Jane Smith's photo URL falls back to /photos/default.jpg.
     * 4. Cache remains stable after 5 consecutive reloadData() calls
     *    (simulates FileWatcher debounce with multiple rapid events).
     */
    @Test
    void testPhotosAreCorrectlyMappedAndStableAcrossReloads() {
        List<Employee> employees = excelService.getAllEmployees();

        assertEquals(2, employees.size(), "Two employees must be loaded from test Excel");

        Employee john = employees.stream()
                .filter(e -> "John Doe".equals(e.getName()))
                .findFirst()
                .orElse(null);
        Employee jane = employees.stream()
                .filter(e -> "Jane Smith".equals(e.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(john, "John Doe must be present");
        assertNotNull(jane, "Jane Smith must be present");

        // Photo resolved from /photos/john.doe@example.com.jpg
        assertEquals(
                "/photos/john.doe@example.com.jpg",
                john.getPhotoUrl(),
                "John Doe must have his photo URL resolved"
        );

        // No file for Jane — expects default
        assertEquals(
                "/photos/default.jpg",
                jane.getPhotoUrl(),
                "Jane Smith must fall back to default photo"
        );

        // Simulate multiple rapid FileWatcher triggers (debounce scenario)
        for (int i = 0; i < 5; i++) {
            excelService.reloadData();
        }

        // Cache must remain consistent after repeated reloads
        List<Employee> afterReload = excelService.getAllEmployees();
        Employee johnAfter = afterReload.stream()
                .filter(e -> "John Doe".equals(e.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(johnAfter, "John Doe must still be present after 5 reloads");
        assertEquals(
                "/photos/john.doe@example.com.jpg",
                johnAfter.getPhotoUrl(),
                "Photo URL must remain stable after multiple cache reloads"
        );
    }
}

