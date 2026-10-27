package cs_orgchart.importer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The report of an Excel import (spec v3 §13 Phase 5c): counts, Excel row numbers, column names and function names
 * only, no names or e-mails. The same in a dry run.
 */
public class ImportReport {

    /** DONE, DRY_RUN, NO_EMPLOYEES (nothing to match), LOCKED (an AD sync or import is running), FAILED */
    public String status;
    public String message;
    public Instant startedAt;
    public boolean dryRun;

    public int excelRows;
    public int emptyRows;
    public int matchedRows;
    public int matchedByEmail;
    public int matchedByUpn;
    public int matchedByLocalPart;
    public List<Integer> localPartRows = new ArrayList<>();
    /** Excel people not found in AD (no e-mail, no match, or an ambiguous local part) */
    public List<Integer> unmatchedRows = new ArrayList<>();
    public List<Integer> ambiguousLocalPartRows = new ArrayList<>();
    public List<Integer> rowsWithoutEmail = new ArrayList<>();
    public List<Integer> disabledEmployeeRows = new ArrayList<>();
    public List<Integer> externalEmployeeRows = new ArrayList<>();
    public List<Integer> rowsWithoutCs = new ArrayList<>();
    public List<Integer> unknownCsRows = new ArrayList<>();
    /** Excel CS values that are not functions of department_function_map */
    public List<String> unknownCsValues = new ArrayList<>();
    /** A second row for the same person and function: fills only what the first left empty */
    public List<Integer> duplicateRows = new ArrayList<>();

    public int rowsCreated;
    public int rowsFilled;
    public int rowsUnchanged;
    /** Rows last saved by someone else (an admin): left exactly as they are */
    public List<Integer> rowsEditedByOthers = new ArrayList<>();
    public Map<String, Integer> fieldsFilled = new TreeMap<>();
    /** A value refused by the rules of admin edits (no < or >, length): not stored, the rest of the row is */
    public List<Rejected> rejectedValues = new ArrayList<>();

    /** Heads set per function (in Excel row order) */
    public Map<String, Integer> headsSet = new TreeMap<>();
    /** Functions that already had heads: their Excel heads are not imported */
    public List<String> headsSkippedFunctions = new ArrayList<>();

    public ManagerOverrides managerOverrides = new ManagerOverrides();
    public List<Integer> pmUnresolvedRows = new ArrayList<>();
    public List<Integer> pmDisabledRows = new ArrayList<>();

    /** Excel title ending with ", XX" while the AD title does not end the same way (country heads, §14) */
    public List<Integer> titleSuffixMismatchRows = new ArrayList<>();
    public DefaultGroups defaultGroups = new DefaultGroups();
    /** Central Services employees of AD that no Excel row matched, per site function */
    public Map<String, Integer> adEmployeesNotInExcelByFunction = new TreeMap<>();

    public record Rejected(int row, String column, String reason) {
    }

    public static class ManagerOverrides {
        public String parentSource;
        /** Matched rows whose Excel PM is another employee than the parent from AD */
        public int candidates;
        public int matchedRows;
        public double ratio;
        public double maxRatio;
        public int created;
        public boolean refused;
        public String message;
    }

    public static class DefaultGroups {
        /** Matched rows with an Excel group (not Head) */
        public int rowsWithGroup;
        public int equalToDefaultGroup;
        public double percent;
    }

    void fill(String field) {
        fieldsFilled.merge(field, 1, Integer::sum);
    }

    /** One line for the log */
    public String summary() {
        return String.format("Excel import %s%s: %d rows, %d matched, %d unmatched, %d created, %d filled, %d left to"
                        + " admins, %d values rejected, %d unknown CS, %d disabled, manager overrides %d of %d%s",
                status, dryRun ? " (dry run, nothing written)" : "", excelRows, matchedRows, unmatchedRows.size(),
                rowsCreated, rowsFilled, rowsEditedByOthers.size(), rejectedValues.size(), unknownCsRows.size(),
                disabledEmployeeRows.size(), managerOverrides.created, managerOverrides.candidates,
                managerOverrides.refused ? " (" + managerOverrides.message + ")" : "")
                + (message == null ? "" : " - " + message);
    }
}
