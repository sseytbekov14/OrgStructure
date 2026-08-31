package cs_orgchart;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

public class UpdateExcelTask {
    public static void main(String[] args) {
        String excelPath = "c:/Projects/OrgStructure/data/result_new.xlsx";
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

            Map<String, Integer> colMap = new HashMap<>();
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    if (cell != null) {
                        String header = cell.toString();
                        if (header != null) {
                            String cleanHeader = header.replace("\u00A0", " ").replaceAll("\\s+", " ").trim().toLowerCase();
                            colMap.put(cleanHeader, c);
                        }
                    }
                }
            }

            int idxName = colMap.getOrDefault("name", 0);
            int idxGroup = colMap.getOrDefault("group", 2);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Cell nameCell = row.getCell(idxName);
                if (nameCell == null) continue;

                String name = nameCell.toString().trim();
                
                if (name.equalsIgnoreCase("Taghiyev, Mubariz")) {
                    Cell groupCell = row.getCell(idxGroup, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    groupCell.setCellValue("IT Security and Infrastructure");
                    System.out.println("Updated Taghiyev, Mubariz group to IT Security and Infrastructure");
                } 
                else if (name.equalsIgnoreCase("Talgatbekov, Nursultan")) {
                    Cell groupCell = row.getCell(idxGroup, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    groupCell.setCellValue("1C Development");
                    System.out.println("Updated Talgatbekov, Nursultan group to 1C Development");
                }
            }

            try (FileOutputStream fos = new FileOutputStream(excelPath)) {
                workbook.write(fos);
            }
            System.out.println("Excel file updated successfully.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
