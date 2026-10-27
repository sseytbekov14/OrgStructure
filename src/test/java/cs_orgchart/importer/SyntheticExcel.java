package cs_orgchart.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SYNTHETIC Excel files of the import tests (mock people of mock/ad-employees.json, no real data), written at test
 * time. Row numbers in the comments are the ones Excel shows (row 1 is the header).
 */
final class SyntheticExcel {

    /** Header variants: NBSP around E-mail, a double space, other casing */
    static final List<String> HEADERS = List.of("Name", " E-mail ", "CS", "group", "Area  of Duties",
            "Job Description", "Job Title", "PM", "PM Email", "PM Job Title");

    static final String LONG = "x".repeat(600);

    /** The rows of the main fixture: E-mail, CS, Group, Area of Duties, Job Description, Job Title, PM Email */
    static List<String[]> fixture() {
        List<String[]> rows = new ArrayList<>();
        rows.add(row("kilo.khaki@kz.example", "ITS", "Head", "IT strategy", "Leads IT", "Chief Information Officer", null)); // 2
        rows.add(row("LIMA.LEMON@KZ.EXAMPLE", "ITS", "HEAD", "Infrastructure", "Runs IT ops", "IT Operations Manager",
                "kilo.khaki@kz.example")); // 3: e-mail in other case; PM = parent in AD
        rows.add(row("mike.mint@kpmg.com", "ITS", "Service Desk", "Workplace IT", "User support", "System Administrator",
                "lima.lemon@kz.example")); // 4: local part only
        rows.add(row("hotel.honey@kz.example", "People", "HR Administration", "HR admin", "Records", null,
                "golf.garnet@kz.example")); // 5
        rows.add(row("hotel.honey@kz.example", "F&O", "Office support", "Office admin", "Access cards", null,
                "charlie.coral@kz.example")); // 6: same person, second function, PM differs from AD
        rows.add(row("nobody@kz.example", "ITS", "Service Desk", "Nothing", null, null, null)); // 7: unmatched
        rows.add(row("november.nickel@kz.example", "People", "KG", "HR", null, null, null)); // 8: disabled in the test
        rows.add(row("november.navy@kz.example", "Sales", "PMO", "Projects", null, null, null)); // 9: unknown CS
        rows.add(row("zulu.zinc@kz.example", "Finance", "Accounting General", "<b>Ledger</b>", LONG, "Accountant",
                null)); // 10: rejected values, group imported
        rows.add(row(" oscar.olive@kz.example ", "ITS", " ", "", "Field support", "IT Specialist, KG",
                null)); // 11: NBSP, empty cells
        rows.add(row("papa.peach@kz.example", "ITS", null, null, "Specialist", "Senior IT Specialist, KG",
                null)); // 12: AD title ends with ", AM"
        rows.add(row("alfa.azure@kz.example", "Finance", "GE", "Accounting GE", null, "Senior Accountant, GE",
                "yankee.yellow@kpmg.com")); // 13: PM by local part, other domain, = parent in AD
        rows.add(row("romeo.ruby@kz.example", "QRM", null, "Risk", null, null, "ghost.pm@kz.example")); // 14: PM unknown
        rows.add(row("romeo.rust@kz.example", "F&O", null, "Partner", null, null, null)); // 15: external employee
        rows.add(row(null, null, null, null, null, null, null)); // 16: empty
        rows.add(row("tango.teal@kz.example", null, null, "Legal", null, null, null)); // 17: no CS
        rows.add(row("kilo.khaki@kz.example", "ITS", "Head", null, "Duplicate", null, null)); // 18: duplicate
        return rows;
    }

    static String[] row(String email, String cs, String group, String area, String description, String title,
                        String pmEmail) {
        return new String[]{"Person", email, cs, group, area, description, title, null, pmEmail, null};
    }

    static Path write(Path file, List<String> headers, List<String[]> rows) throws IOException {
        Files.createDirectories(file.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("old sheet, not read");
            Sheet sheet = workbook.createSheet("NEW");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                String[] values = rows.get(r);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] != null) {
                        row.createCell(c).setCellValue(values[c]);
                    }
                }
            }
            workbook.write(out);
        }
        return file;
    }

    static Path fixture(Path dir) throws IOException {
        return write(dir.resolve("result_new.xlsx"), HEADERS, fixture());
    }

    static List<String> headersWithout(String header) {
        List<String> headers = new ArrayList<>(HEADERS);
        headers.removeIf(h -> h.trim().equalsIgnoreCase(header));
        return headers;
    }

    static List<String[]> withoutColumn(List<String[]> rows, int column) {
        return rows.stream().map(r -> {
            List<String> values = new ArrayList<>(Arrays.asList(r));
            values.remove(column);
            return values.toArray(String[]::new);
        }).toList();
    }
}
