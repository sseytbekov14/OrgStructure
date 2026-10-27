package cs_orgchart.importer;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rows of sheet NEW of the Excel file, for the import (spec v3 §13 Phase 5c): columns found by their header name
 * only (no column positions), values trimmed, NBSP as a space, empty as null. The file is opened read-only and never
 * saved.
 */
final class ExcelOverlaySheet {

    static final String SHEET = "NEW";
    static final String EMAIL = "e-mail";
    static final String CS = "cs";
    static final String GROUP = "group";
    static final String AREA = "area of duties";
    static final String DESCRIPTION = "job description";
    static final String JOB_TITLE = "job title";
    static final String PM_EMAIL = "pm email";

    /** One row of the sheet; {@code number} is the row number Excel shows */
    record ExcelRow(int number, String email, String cs, String group, String areaOfDuties, String jobDescription,
                    String jobTitle, String pmEmail) {

        boolean empty() {
            return email == null && cs == null && group == null && areaOfDuties == null && jobDescription == null
                    && jobTitle == null && pmEmail == null;
        }
    }

    /** The data rows and the column names as written in the file (for the report) */
    record Content(List<ExcelRow> rows, Map<String, String> headers) {
    }

    private ExcelOverlaySheet() {
    }

    /**
     * @throws ImportException when the file, sheet NEW or a required column (E-mail, CS) is missing
     */
    static Content read(File file) {
        if (!file.isFile()) {
            throw new ImportException("the Excel file " + file + " does not exist (app.data.excel-path)");
        }
        OPCPackage pkg;
        try {
            pkg = OPCPackage.open(file, PackageAccess.READ);
        } catch (Exception e) {
            throw new ImportException("the Excel file " + file + " cannot be opened: " + e.getMessage());
        }
        try {
            XSSFWorkbook workbook = new XSSFWorkbook(pkg);
            Sheet sheet = null;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (SHEET.equalsIgnoreCase(workbook.getSheetName(i).trim())) {
                    sheet = workbook.getSheetAt(i);
                }
            }
            if (sheet == null) {
                throw new ImportException("the Excel file has no sheet " + SHEET);
            }
            Map<String, Integer> columns = new HashMap<>();
            Map<String, String> headers = new HashMap<>();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header != null) {
                for (Cell cell : header) {
                    String text = text(cell);
                    if (text != null) {
                        String key = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                        columns.putIfAbsent(key, cell.getColumnIndex());
                        headers.putIfAbsent(key, text);
                    }
                }
            }
            if (!columns.containsKey(EMAIL) && columns.containsKey("email")) {
                columns.put(EMAIL, columns.get("email"));
                headers.put(EMAIL, headers.get("email"));
            }
            for (String required : List.of(EMAIL, CS)) {
                if (!columns.containsKey(required)) {
                    throw new ImportException("sheet " + SHEET + " has no column '" + required + "' (columns are"
                            + " found by their header names only)");
                }
            }
            List<ExcelRow> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                rows.add(new ExcelRow(r + 1, value(row, columns, EMAIL), value(row, columns, CS),
                        value(row, columns, GROUP), value(row, columns, AREA), value(row, columns, DESCRIPTION),
                        value(row, columns, JOB_TITLE), value(row, columns, PM_EMAIL)));
            }
            return new Content(rows, headers);
        } catch (ImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportException("the Excel file cannot be read: " + e.getMessage());
        } finally {
            // Read-only: discard (the workbook is not closed, so nothing can save the package)
            pkg.revert();
        }
    }

    private static String value(Row row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        return index == null ? null : text(row.getCell(index));
    }

    /** Trimmed text of the cell, NBSP as a space, null when empty */
    static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String text = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> new DataFormatter().formatRawCellContents(cell.getNumericCellValue(), -1, "General");
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        if (text == null) {
            return null;
        }
        text = text.replace(' ', ' ').trim();
        return text.isEmpty() ? null : text;
    }
}
