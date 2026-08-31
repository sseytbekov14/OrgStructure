package cs_orgchart.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;

/**
 * Utility to add Area of Duties and Job Description columns to the Excel file.
 * Run once, then delete this file.
 */
public class AddColumnsUtil {

    // Data: row index (0-based from data rows, i.e. row 2 in Excel = index 0)
    // Format: {areaOfDuties, jobDescription}
    // 214 data rows total (rows 2-215 in Excel)
    static final String[][] DATA = {
        {"People", "People General"},            // row 2  - Solodova, Maria
        {"People", "People General"},            // row 3  - Spirin, Danil
        {"Learning & Development", "Learning & Development"},
        {"People", "HR Administration"},
        {"Talent Acquisition", "Graduate Recruiting"},
        {"Compensation & Benefits", "Compensation & Benefits"},
        {"", ""},
        {"People", "People General"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Talent Acquisition", "Graduate Recruiting"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Talent Acquisition", "Graduate Recruiting"},
        {"Talent Acquisition", "Graduate Recruiting"},
        {"Talent Acquisition", "Employer Brand & Talent Attraction"},
        {"Talent Acquisition", "Experienced Recruiting"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"Learning & Development", "Learning & Development"},
        {"People", "HR Administration"},
        {"People", "HR Administration"},
        {"People", "HR Administration"},
        {"People", "HR Administration"},
        {"People", "HR Administration"},
        {"People", "People General"},
        {"", ""},
        {"", ""},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"People", "People General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "F&O General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Accounting General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Finance General", "Finance General"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Brand & Hospitality"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"Markets General", "Markets General"},
        {"People", "People General"},
        {"People", "People General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"Office of General Counsel", "OGC General"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"QRM", "QRM"},
        {"Facilities & Operations", "Tenders"},
        {"Facilities & Operations", "Tenders"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "Tenders"},
        {"Facilities & Operations", "Tenders"},
        {"Facilities & Operations", "Proposals & Tenders"},
        {"Facilities & Operations", "F&O General"},
        {"Facilities & Operations", "Tenders"},
        {"Facilities & Operations", "Proposals & Tenders"},
        {"ITS General", "ITS General"},
        {"ITS General", "IT Operations"},
        {"IT PMO", "IT PMO"},
        {"ITS General", "IT Operations"},
        {"ITS General", "IT Operations"},
        {"ITS General", "IT Operations"},
        {"IT PMO", "IT PMO"},
        {"ITS General", "IT PMO"},
        {"ITS General", "IT Operations"},
        {"ITS General", "IT Operations"},
        {"ITS General", "Service Desk"},
        {"ITS General", "Infra & Hosting"},
        {"ITS General", "Infra & Hosting"},
        {"ITS General", "Infra & Hosting"},
        {"ITS General", "IT Operations"},
        {"IT PMO", "IT PMO"},
        {"IT PMO", "IT PMO"},
        {"ITS General", "Service Desk"},
        {"ITS General", "Service Desk"},
        {"ITS General", "Service Desk"},
        {"ITS General", "Service Desk"},
        {"ITS General", "ITS General"},
        {"ITS General", "ITS General"},
        {"ITS General", "ITS General"},
        {"ITS General", "ITS General"},
        {"ITS General", "ITS General"},
        {"ITS General", "Infra & Hosting"},
        {"ITS General", "Infra & Hosting"},
        {"ITS General", "Infra & Hosting"},
    };

    public static void main(String[] args) throws Exception {
        String filePath = "C:/Projects/OrgStructure/data/result_new.xlsx";

        FileInputStream fis = new FileInputStream(filePath);
        Workbook workbook = new XSSFWorkbook(fis);
        fis.close();

        Sheet sheet = workbook.getSheetAt(0);

        // Add headers in row 1 (index 0)
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) headerRow = sheet.createRow(0);

        Cell headerI = headerRow.getCell(8);
        if (headerI == null) headerI = headerRow.createCell(8);
        headerI.setCellValue("Area of Duties");

        Cell headerJ = headerRow.getCell(9);
        if (headerJ == null) headerJ = headerRow.createCell(9);
        headerJ.setCellValue("Job Description");

        // Fill data rows (starting from row index 1 = Excel row 2)
        for (int i = 0; i < DATA.length; i++) {
            int rowIndex = i + 1;
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);

            Cell cellI = row.getCell(8);
            if (cellI == null) cellI = row.createCell(8);
            cellI.setCellValue(DATA[i][0]);

            Cell cellJ = row.getCell(9);
            if (cellJ == null) cellJ = row.createCell(9);
            cellJ.setCellValue(DATA[i][1]);
        }

        FileOutputStream fos = new FileOutputStream(filePath);
        workbook.write(fos);
        fos.close();
        workbook.close();

        System.out.println("Done! Updated " + DATA.length + " rows.");
    }
}
