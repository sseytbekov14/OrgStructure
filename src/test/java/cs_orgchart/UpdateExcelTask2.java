package cs_orgchart;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

public class UpdateExcelTask2 {
    public static void main(String[] args) {
        String excelPath = "c:/Projects/OrgStructure/data/result_new.xlsx";
        try (FileInputStream fis = new FileInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            Map<String, Integer> colMap = new HashMap<>();
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    if (cell != null) {
                        String header = cell.toString().toLowerCase().trim();
                        colMap.put(header, c);
                    }
                }
            }

            int idxName = colMap.getOrDefault("name", 0);
            int idxPm = colMap.getOrDefault("pm", 5);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Cell nameCell = row.getCell(idxName);
                if (nameCell == null) continue;

                String name = nameCell.toString().trim();
                
                if (name.equalsIgnoreCase("Baimukhametov, Timur")) {
                    Cell pmCell = row.getCell(idxPm, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    pmCell.setCellValue("Sadykulov, Bolat");
                    System.out.println("Updated Baimukhametov, Timur PM to Sadykulov, Bolat");
                }
            }

            try (FileOutputStream fos = new FileOutputStream(excelPath)) {
                workbook.write(fos);
                System.out.println("Excel file updated successfully.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
