package cs_orgchart.ad.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * src/test/resources/departments.csv: the real AD department values of the spec §4.2 filter with their number of
 * accounts (PowerShell Export-Csv, spec §5.5; no personal data), and the golden file of the normalized results.
 */
public final class DepartmentsCsv {

    public record Row(int count, String department) {
    }

    private DepartmentsCsv() {
    }

    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        List<String> lines = lines("/departments.csv");
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            int comma = line.indexOf(',');
            rows.add(new Row(Integer.parseInt(unquote(line.substring(0, comma))), unquote(line.substring(comma + 1))));
        }
        return rows;
    }

    /** The 20 normalized departments of spec Appendix A */
    public static List<String> golden() {
        return lines("/departments-normalized.golden.txt").stream().filter(l -> !l.isBlank()).toList();
    }

    private static List<String> lines(String resource) {
        try (InputStream in = DepartmentsCsv.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            return text.lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException(resource + " cannot be read", e);
        }
    }

    private static String unquote(String value) {
        String v = value.trim();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1).replace("\"\"", "\"") : v;
    }
}
