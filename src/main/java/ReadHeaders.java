import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.FileInputStream;

public class ReadHeaders {
    public static void main(String[] args) throws Exception {
        FileInputStream fis = new FileInputStream("C:/Projects/OrgStructure/data/result_new.xlsx");
        Workbook workbook = new XSSFWorkbook(fis);
        Sheet sheet = workbook.getSheetAt(0);
        
        System.out.println("--- HEADERS ---");
        Row headerRow = sheet.getRow(0);
        if (headerRow != null) {
            for (int i = 0; i < 15; i++) {
                Cell c = headerRow.getCell(i);
                if (c != null) System.out.println(i + ": " + c.toString());
            }
        }
        
        System.out.println("--- ROW 2 (Solodova) ---");
        Row r1 = sheet.getRow(1);
        if (r1 != null) {
            for (int i = 0; i < 15; i++) {
                Cell c = r1.getCell(i);
                if (c != null) System.out.println(i + ": " + c.toString());
            }
        }

        System.out.println("--- ROW match Seytbekov ---");
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getCell(0) != null && row.getCell(0).toString().contains("Seytbekov")) {
                for (int i = 0; i < 15; i++) {
                    Cell c = row.getCell(i);
                    if (c != null) System.out.println(i + ": " + c.toString());
                }
                break;
            }
        }
        
        workbook.close();
        fis.close();
    }
}
