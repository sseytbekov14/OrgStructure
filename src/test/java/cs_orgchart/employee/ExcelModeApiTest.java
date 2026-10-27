package cs_orgchart.employee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EMPLOYEE_SOURCE=excel (the default): the API answers exactly as before Phase 5, from the Excel file, without the
 * database fields.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExcelModeApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void excel(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("orgchart-excel-mode");
        Path file = dir.resolve("result_new.xlsx");
        Files.createDirectories(dir.resolve("photos"));
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("NEW");
            String[] headers = {"Name", "E-mail", "CS", "Group", "Area of Duties", "Job Description", "Job Title", "PM",
                    "PM Email", "PM Job Title"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            String[][] rows = {
                    {"Doe, John", "john.doe@corp.example", "ITS", "HEAD", "IT", "Leadership", "Head of IT", "Boss, Big",
                            "big.boss@corp.example", "Partner"},
                    {"Roe, Jane", "jane.roe@corp.example", "ITS", "UZ", "Support", "Help desk", "IT Specialist, UZ",
                            "Doe, John", "john.doe@corp.example", "Head of IT"}};
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
        }
        registry.add("app.data.excel-path", file::toString);
        registry.add("app.data.photos-path", () -> dir.resolve("photos").toString());
    }

    @Test
    void employeesComeFromExcelWithExactlyThePreviousFields() throws Exception {
        List<Map<String, Object>> rows = json.readValue(mvc.perform(get("/api/employees")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), new TypeReference<>() {
        });

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.keySet())
                .containsExactlyInAnyOrderElementsOf(DbEmployeeDirectoryTest.PREVIOUS_FIELDS));
        assertThat(rows.get(1)).containsEntry("name", "Roe, Jane").containsEntry("cs", "ITS").containsEntry("group", "UZ")
                .containsEntry("pm", "Doe, John").containsEntry("pmEmail", "john.doe@corp.example")
                .containsEntry("photoUrl", "/photos/default.jpg").containsEntry("gradeOrder", 6)
                .containsEntry("areaOfDuties", "Support").containsEntry("jobDescription", "Help desk");
    }

    @Test
    void searchAndFunctionsWorkAsBefore() throws Exception {
        String found = mvc.perform(get("/api/search").param("q", "help desk")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readValue(found, new TypeReference<List<Map<String, Object>>>() {
        })).extracting(row -> row.get("name")).containsExactly("Roe, Jane");
        assertThat(mvc.perform(get("/api/functions")).andReturn().getResponse().getContentAsString())
                .isEqualTo("[\"ITS\"]");
    }

    @Test
    void noDatabaseDirectoryInExcelMode() {
        assertThat(context.getBeanNamesForType(DbEmployeeDirectory.class)).isEmpty();
    }
}
