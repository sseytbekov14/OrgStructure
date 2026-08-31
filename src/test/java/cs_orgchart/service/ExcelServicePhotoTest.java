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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("local")
class ExcelServicePhotoTest {

    @MockBean
    ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private ExcelService excelService;

    private static Path tempDir;
    private static Path excelFile;
    private static Path photosDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        tempDir = Files.createTempDirectory("orgchart-test");
        excelFile = tempDir.resolve("test_data.xlsx");
        photosDir = tempDir.resolve("photos");
        Files.createDirectories(photosDir);

        // Переопределяем пути для Excel и фотографий на временные
        registry.add("app.data.excel-path", () -> excelFile.toAbsolutePath().toString());
        registry.add("app.data.photos-path", () -> photosDir.toAbsolutePath().toString());
    }

    @BeforeEach
    void setUp() throws IOException {
        // Создаем Excel-файл с тестовыми сотрудниками
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("NEW");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("cs");
            header.createCell(2).setCellValue("email");
            header.createCell(3).setCellValue("group");
            header.createCell(4).setCellValue("job title");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("John Doe");
            row1.createCell(1).setCellValue("IT");
            row1.createCell(2).setCellValue("john.doe@example.com");
            row1.createCell(3).setCellValue("Backend");
            row1.createCell(4).setCellValue("Developer");

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Jane Smith");
            row2.createCell(1).setCellValue("HR");
            row2.createCell(2).setCellValue("jane.smith@example.com");
            row2.createCell(3).setCellValue("Recruitment");
            row2.createCell(4).setCellValue("HR Manager");

            try (FileOutputStream fos = new FileOutputStream(excelFile.toFile())) {
                workbook.write(fos);
            }
        }

        // Создаем тестовую фотографию только для John Doe (полный email)
        Files.writeString(photosDir.resolve("john.doe@example.com.jpg"), "dummy image content");
        
        // Явно просим сервис обновить данные (как при запуске приложения)
        excelService.reloadData();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(photosDir.resolve("john.doe@example.com.jpg"));
        Files.deleteIfExists(excelFile);
    }

    @Test
    void testPhotosAreCorrectlyMappedAndStable() {
        List<Employee> employees = excelService.getAllEmployees();
        
        assertEquals(2, employees.size(), "Должно быть загружено 2 сотрудника");

        Employee john = employees.stream().filter(e -> "John Doe".equals(e.getName())).findFirst().orElse(null);
        Employee jane = employees.stream().filter(e -> "Jane Smith".equals(e.getName())).findFirst().orElse(null);

        assertNotNull(john, "John Doe должен существовать");
        assertNotNull(jane, "Jane Smith должна существовать");

        // 1. Проверяем, что при старте фотография John успешно замапилась
        assertEquals("/photos/john.doe@example.com.jpg", john.getPhotoUrl(), "У John Doe должна быть установлена фотография");
        
        // 2. Проверяем, что для Jane установилась фотография по умолчанию
        assertEquals("/photos/default.jpg", jane.getPhotoUrl(), "У Jane Smith должна быть дефолтная фотография");

        // 3. Симулируем множественные вызовы обновления (как при ложных срабатываниях FileWatcher)
        for (int i = 0; i < 5; i++) {
            excelService.reloadData();
        }

        // 4. Проверяем "стабильность" и долговечность данных после обновлений
        List<Employee> employeesAfterReload = excelService.getAllEmployees();
        Employee johnAfter = employeesAfterReload.stream().filter(e -> "John Doe".equals(e.getName())).findFirst().orElse(null);
        assertNotNull(johnAfter);
        assertEquals("/photos/john.doe@example.com.jpg", johnAfter.getPhotoUrl(), "Фотография John Doe должна оставаться стабильной после множественных перезагрузок кэша");
    }
}
