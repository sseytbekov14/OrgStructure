package cs_orgchart.service;

import cs_orgchart.model.Employee;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ExcelService {

    @Value("${app.data.excel-path}")
    private String excelPath;

    @Value("${app.data.photos-path}")
    private String photosPath;

    private volatile List<Employee> employees = List.of();

    private Comparator<Employee> employeeHierarchyComparator() {
        return Comparator
                .comparingInt(Employee::getGradeOrder)
                .thenComparing(Employee::getJobTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(Employee::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    @PostConstruct
    public void loadData() {
        reloadData();
    }

    public synchronized void reloadData() {
        try {
            Map<String, String> photoFilesMap = new HashMap<>();
            File photosDir = new File(photosPath);
            if (photosDir.exists() && photosDir.isDirectory()) {
                File[] files = photosDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        photoFilesMap.put(f.getName().toLowerCase(), f.getName());
                    }
                }
            }

            List<Employee> loadedEmployees = readEmployeesFromExcel(photoFilesMap);
            employees = List.copyOf(loadedEmployees);
            log.info("Loaded {} employees from Excel file", employees.size());
        } catch (IOException e) {
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
                        String cleanHeader = header.replace("\u00A0", " ").replaceAll("\\s+", " ").trim().toLowerCase();
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

                String photoUrl = resolvePhotoUrl(email, photoFilesMap);

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
                        .gradeOrder(getGradeOrder(jobTitle))
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

    private String resolvePhotoUrl(String email, Map<String, String> photoFilesMap) {
        if (email == null || email.trim().isEmpty()) {
            return "/photos/default.jpg";
        }
        
        String cleanEmail = email.trim().toLowerCase();
        int atIndex = cleanEmail.indexOf('@');
        String username = atIndex > 0 ? cleanEmail.substring(0, atIndex) : cleanEmail;
        
        String[] extensions = {".jpg", ".png", ".jpeg", ".webp"};
        
        // 1. Check full email with extensions
        for (String ext : extensions) {
            String exactName = photoFilesMap.get(cleanEmail + ext);
            if (exactName != null) {
                return "/photos/" + exactName;
            }
        }
        
        // 2. Check username with extensions
        for (String ext : extensions) {
            String exactName = photoFilesMap.get(username + ext);
            if (exactName != null) {
                return "/photos/" + exactName;
            }
        }
        
        return "/photos/default.jpg";
    }

    public List<Employee> getAllEmployees() {
        return new ArrayList<>(employees);
    }

    public List<String> getFunctions() {
        return employees.stream()
                .map(Employee::getCs)
                .filter(cs -> cs != null && !cs.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private int getGradeOrder(String jobTitle) {
        if (jobTitle == null) return 99;
        String title = jobTitle.toLowerCase();
        if (title.contains("chief") || title.contains("head") || title.contains("leader")) return 1;
        if (title.contains("deputy")) return 2;
        if (title.contains("senior") && title.contains("manager")) return 3;
        if (title.contains("manager")) return 4;
        if (title.contains("supervisor")) return 5;
        if (title.contains("senior")) return 5;
        if (title.contains("junior")) return 7;
        if (title.contains("intern")) return 8;
        if (title.contains("trainee")) return 9;
        return 6;
    }

    public List<Employee> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        String lowerQuery = query.toLowerCase().trim();
        boolean searchPmOnly = false;
        String searchTarget = lowerQuery;
        
        if (lowerQuery.startsWith("pm:") || lowerQuery.startsWith("pm ")) {
            searchPmOnly = true;
            searchTarget = lowerQuery.substring(3).trim();
        } else if (lowerQuery.startsWith("manager:") || lowerQuery.startsWith("manager ")) {
            searchPmOnly = true;
            searchTarget = lowerQuery.substring(8).trim();
        }
        
        String cleanQuery = searchTarget.replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String[] tokens = cleanQuery.trim().split("\\s+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            return new ArrayList<>();
        }

        final boolean finalSearchPmOnly = searchPmOnly;
        return employees.stream()
            .filter(e -> {
                String searchableText;
                if (finalSearchPmOnly) {
                    searchableText = String.join(" ",
                        e.getPm() != null ? e.getPm() : "",
                        e.getPmEmail() != null ? e.getPmEmail() : "",
                        e.getPmJobTitle() != null ? e.getPmJobTitle() : ""
                    ).toLowerCase();
                } else {
                    searchableText = String.join(" ",
                        e.getName() != null ? e.getName() : "",
                        e.getEmail() != null ? e.getEmail() : "",
                        e.getCs() != null ? e.getCs() : "",
                        e.getGroup() != null ? e.getGroup() : "",
                        e.getAreaOfDuties() != null ? e.getAreaOfDuties() : "",
                        e.getJobDescription() != null ? e.getJobDescription() : "",
                        e.getJobTitle() != null ? e.getJobTitle() : ""
                    ).toLowerCase();
                }
                
                String cleanSearchableText = searchableText.replaceAll("[^\\p{L}\\p{N}\\s]", " ");
                
                for (String token : tokens) {
                    if (!cleanSearchableText.contains(token) && !searchableText.contains(token)) {
                        return false;
                    }
                }
                return true;
            })
            .sorted(employeeHierarchyComparator())
            .collect(Collectors.toList());
    }

    public List<Employee> getEmployeesByFunction(String cs) {
        return employees.stream()
                .filter(e -> e.getCs() != null && e.getCs().equals(cs))
                .sorted(employeeHierarchyComparator())
                .collect(Collectors.toList());
    }
}
