package cs_orgchart.service;

import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.employee.EmployeePhotos;
import cs_orgchart.employee.EmployeeSourceProperties;
import cs_orgchart.employee.GradeOrder;
import cs_orgchart.model.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * EMPLOYEE_SOURCE=excel: the employees of the Excel file into the {@link EmployeeDirectory}. With
 * EMPLOYEE_SOURCE=db the file is never read.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExcelService {

    @Value("${app.data.excel-path:}")
    private String excelPath;

    private final EmployeeDirectory directory;
    private final EmployeePhotos photos;
    private final EmployeeSourceProperties employeeSource;

    @PostConstruct
    public void loadData() {
        if (employeeSource.isDb()) {
            log.info("EMPLOYEE_SOURCE=db: the Excel file is not read");
            return;
        }
        reloadData();
    }

    public synchronized void reloadData() {
        if (employeeSource.isDb()) {
            return;
        }
        File file = new File(excelPath);
        if (!file.exists()) {
            log.warn("Excel file not found, skipping load: {}", excelPath);
            return;
        }
        if (file.length() == 0) {
            log.warn("Excel file is empty (0 bytes), skipping load: {}", excelPath);
            return;
        }

        try {
            Map<String, String> photoFilesMap = photos.files();

            List<Employee> loadedEmployees = readEmployeesFromExcel(photoFilesMap);
            directory.replace(loadedEmployees);
            log.info("Loaded {} employees from Excel file", loadedEmployees.size());
        } catch (Exception e) {
            log.error("Error loading Excel file: {}", e.getMessage(), e);
        }
    }

    private List<Employee> readEmployeesFromExcel(Map<String, String> photoFilesMap) throws IOException {
        List<Employee> loadedEmployees = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = null;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if ("NEW".equalsIgnoreCase(workbook.getSheetName(i))) {
                    sheet = workbook.getSheetAt(i);
                    break;
                }
            }
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }

            // Динамически читаем названия колонок из первой строки
            Map<String, Integer> colMap = new HashMap<>();
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    String header = getCellValue(headerRow.getCell(c));
                    if (header != null) {
                        String cleanHeader = header.replace(" ", " ").replaceAll("\\s+", " ").trim().toLowerCase();
                        colMap.put(cleanHeader, c);
                    }
                }
            }

            // Ищем индексы колонок по названию, с фолбэком на дефолтные значения
            int idxName = colMap.getOrDefault("name", 0);
            int idxCs = colMap.getOrDefault("cs", 1);
            int idxGroup = colMap.getOrDefault("group", 2);
            int idxJobTitle = colMap.getOrDefault("job title", 3);
            int idxEmail = colMap.getOrDefault("e-mail", colMap.getOrDefault("email", 4));
            int idxPm = colMap.getOrDefault("pm", 5);
            int idxPmEmail = colMap.getOrDefault("pm email", 6);
            int idxPmJobTitle = colMap.getOrDefault("pm job title", 7);
            int idxArea = colMap.getOrDefault("area of duties", 8);
            int idxDesc = colMap.getOrDefault("job description", 9);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String name = getCellValue(row.getCell(idxName));
                String cs = getCellValue(row.getCell(idxCs));
                String group = getCellValue(row.getCell(idxGroup));
                String jobTitle = getCellValue(row.getCell(idxJobTitle));
                String email = getCellValue(row.getCell(idxEmail));
                String pm = getCellValue(row.getCell(idxPm));
                String pmEmail = getCellValue(row.getCell(idxPmEmail));
                String pmJobTitle = getCellValue(row.getCell(idxPmJobTitle));
                String areaOfDuties = getCellValue(row.getCell(idxArea));
                String jobDescription = getCellValue(row.getCell(idxDesc));

                if (name == null || name.trim().isEmpty()
                        || cs == null || cs.trim().isEmpty()
                        || email == null || email.trim().isEmpty()) {
                    continue;
                }

                String photoUrl = EmployeePhotos.urlFor(email, photoFilesMap);

                loadedEmployees.add(Employee.builder()
                        .name(name)
                        .cs(cs)
                        .group(group)
                        .jobTitle(jobTitle)
                        .email(email)
                        .pm(pm)
                        .pmEmail(pmEmail)
                        .pmJobTitle(pmJobTitle)
                        .photoUrl(photoUrl)
                        .gradeOrder(GradeOrder.of(jobTitle))
                        .areaOfDuties(areaOfDuties)
                        .jobDescription(jobDescription)
                        .build());
            }
        }

        return loadedEmployees;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((int) cell.getNumericCellValue());
            default -> null;
        };
    }

    /** The employees of the directory (the ones of the file in excel mode) */
    public List<Employee> getAllEmployees() {
        return directory.getAllEmployees();
    }
}
