package cs_orgchart.importer;

import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.department.DepartmentAliasRepository;
import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.department.DepartmentGeneralNodeRepository;
import cs_orgchart.department.JpaDepartmentDictionaries;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeDtoAssembler;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.EmployeeSourceProperties;
import cs_orgchart.employee.EmployeeSourceProperties.ParentSource;
import cs_orgchart.employee.override.AdminEditException;
import cs_orgchart.employee.group.FunctionGroup;
import cs_orgchart.employee.group.FunctionGroupService;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.override.OverlayChanged;
import cs_orgchart.employee.override.OverrideAdminService;
import cs_orgchart.importer.ExcelOverlaySheet.ExcelRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Excel → employee_overrides, once, on a database that holds the AD employees (spec v3 §13 Phase 5c).
 * <ul>
 *   <li>each row is matched by lower(e-mail), lower(UPN), then a unique local part of the e-mail; an ambiguous local
 *       part is no match. Disabled or external employees and unknown functions (CS) are skipped and reported;</li>
 *   <li>one overlay row per Excel row (person and function): Group "Head" gives head_rank in Excel row order, only
 *       for functions without heads; other groups, area of duties and job description pass the rules of admin edits
 *       (a refused value is reported, the rest of the row imported);</li>
 *   <li>a manager override where the Excel PM is another employee than the parent from AD (the rule of the site,
 *       ORG_PARENT_SOURCE with fallback), unless too many rows would need one (then none);</li>
 *   <li>only empty fields are filled, and only in rows the import created (updated_by = {@value #IMPORTED_BY}): a
 *       row last saved by an admin is left as it is, so {@code DELETE FROM employee_overrides WHERE updated_by =
 *       'excel-import'} undoes the import without touching admin edits;</li>
 *   <li>one transaction under the lock of the AD sync; a dry run plans the same and writes nothing.</li>
 * </ul>
 */
@Slf4j
@Service
public class OverlayImportService {

    public static final String IMPORTED_BY = "excel-import";
    static final String HEAD = "head";
    private static final Pattern TITLE_SUFFIX = Pattern.compile(",\\s*([A-Za-z]{2})$");

    private final EmployeeRepository employees;
    private final EmployeeOverrideRepository overlay;
    private final DepartmentFunctionMapRepository functionMap;
    private final DepartmentAliasRepository aliases;
    private final DepartmentGeneralNodeRepository generalNodes;
    private final EmployeeSourceProperties source;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ApplicationEventPublisher events;
    private final FunctionGroupService groups;

    public OverlayImportService(EmployeeRepository employees, EmployeeOverrideRepository overlay,
                                DepartmentFunctionMapRepository functionMap, DepartmentAliasRepository aliases,
                                DepartmentGeneralNodeRepository generalNodes, EmployeeSourceProperties source,
                                JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                ApplicationEventPublisher events, FunctionGroupService groups) {
        this.groups = groups;
        this.employees = employees;
        this.overlay = overlay;
        this.functionMap = functionMap;
        this.aliases = aliases;
        this.generalNodes = generalNodes;
        this.source = source;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
    }

    /**
     * @param dryRun   true: everything in memory, nothing written (the transaction is read-only and rolled back)
     * @param maxRatio more matched rows needing a manager override than this share: none is created
     */
    public ImportReport run(File excel, boolean dryRun, double maxRatio) {
        ImportReport report = new ImportReport();
        report.startedAt = Instant.now();
        report.dryRun = dryRun;
        report.managerOverrides.maxRatio = maxRatio;
        report.managerOverrides.parentSource = source.orgParentSource().name();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(dryRun);
        try {
            transaction.executeWithoutResult(status -> {
                if (dryRun) {
                    status.setRollbackOnly();
                } else if (!tryLock()) {
                    report.status = "LOCKED";
                    report.message = "an AD sync or another import is running; nothing was imported, start again"
                            + " when it has finished";
                    return;
                }
                if (employees.countByEnabledTrueAndExternalFalse() == 0) {
                    report.status = "NO_EMPLOYEES";
                    report.message = "the database has no synced AD employee to match the Excel rows to; run an AD"
                            + " sync first. Nothing was imported";
                    return;
                }
                boolean changed = importRows(ExcelOverlaySheet.read(excel), report, dryRun, maxRatio);
                if (!dryRun && changed) {
                    overlay.flush();
                    // The directory is rebuilt after this transaction committed
                    events.publishEvent(new OverlayChanged(null));
                }
                report.status = dryRun ? "DRY_RUN" : "DONE";
            });
        } catch (ImportException e) {
            report.status = "FAILED";
            report.message = e.getMessage() + "; nothing was imported";
        } catch (RuntimeException e) {
            log.error("Excel import failed, nothing was imported", e);
            report.status = "FAILED";
            report.message = e.getClass().getSimpleName() + ": " + e.getMessage() + "; nothing was imported";
        }
        return report;
    }

    private boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                AdSyncService.LOCK_KEY));
    }

    /** A matched, importable row */
    private record Match(ExcelRow row, Employee employee, boolean head) {
    }

    private boolean importRows(ExcelOverlaySheet.Content content, ImportReport report, boolean dryRun,
                               double maxRatio) {
        List<Employee> all = employees.findAll();
        Map<Long, Employee> byId = new HashMap<>();
        all.forEach(e -> byId.put(e.getId(), e));
        Set<String> functions = new HashSet<>(functionMap.findCsNames());
        JpaDepartmentDictionaries dictionaries = new JpaDepartmentDictionaries(aliases, generalNodes, functionMap);
        dictionaries.reload();
        FunctionResolver resolver = new FunctionResolver(dictionaries);
        Index index = new Index(all);
        Map<String, EmployeeOverride> rows = new HashMap<>();
        Set<String> functionsWithHeads = new HashSet<>();
        for (EmployeeOverride o : overlay.findAll()) {
            rows.put(key(o.getEmployeeId(), o.getCs()), o);
            if (o.getHeadRank() != null) {
                functionsWithHeads.add(o.getCs());
            }
        }

        // 1. Match and check every row
        List<Match> matches = new ArrayList<>();
        Set<Long> inExcel = new HashSet<>();
        Set<String> unknownCs = new LinkedHashSet<>();
        for (ExcelRow row : content.rows()) {
            if (row.empty()) {
                report.emptyRows++;
                continue;
            }
            report.excelRows++;
            if (row.email() == null) {
                report.rowsWithoutEmail.add(row.number());
                report.unmatchedRows.add(row.number());
                continue;
            }
            Lookup found = index.find(row.email());
            if (found.employee() == null) {
                report.unmatchedRows.add(row.number());
                if (found.ambiguous()) {
                    report.ambiguousLocalPartRows.add(row.number());
                }
                continue;
            }
            Employee employee = found.employee();
            report.matchedRows++;
            switch (found.how()) {
                case "EMAIL" -> report.matchedByEmail++;
                case "UPN" -> report.matchedByUpn++;
                default -> {
                    report.matchedByLocalPart++;
                    report.localPartRows.add(row.number());
                    log.info("Excel import: row {} matched employee id {} by the local part of the e-mail only",
                            row.number(), employee.getId());
                }
            }
            if (employee.isExternal()) {
                report.externalEmployeeRows.add(row.number());
                continue;
            }
            if (!employee.isEnabled()) {
                report.disabledEmployeeRows.add(row.number());
                continue;
            }
            inExcel.add(employee.getId());
            if (row.cs() == null) {
                report.rowsWithoutCs.add(row.number());
                continue;
            }
            if (!functions.contains(row.cs())) {
                report.unknownCsRows.add(row.number());
                unknownCs.add(row.cs());
                continue;
            }
            matches.add(new Match(row, employee, row.group() != null && row.group().equalsIgnoreCase(HEAD)));
        }
        report.unknownCsValues.addAll(unknownCs);

        // 2. Manager overrides: where the Excel PM is another employee than the parent from AD
        ParentSource parentSource = source.orgParentSource();
        Map<ExcelRow, Long> overrideFor = new HashMap<>();
        for (Match match : matches) {
            if (match.row().pmEmail() == null) {
                continue;
            }
            Employee pm = index.find(match.row().pmEmail()).employee();
            if (pm == null) {
                report.pmUnresolvedRows.add(match.row().number());
                continue;
            }
            if (!pm.isEnabled()) {
                report.pmDisabledRows.add(match.row().number());
                continue;
            }
            if (pm.getId().equals(match.employee().getId())) {
                continue;
            }
            Employee adParent = EmployeeDtoAssembler.adParent(match.employee(), byId, parentSource);
            if (adParent == null || !adParent.getId().equals(pm.getId())) {
                overrideFor.put(match.row(), pm.getId());
            }
        }
        ImportReport.ManagerOverrides overrides = report.managerOverrides;
        overrides.candidates = overrideFor.size();
        overrides.matchedRows = matches.size();
        overrides.ratio = matches.isEmpty() ? 0 : round((double) overrideFor.size() / matches.size(), 3);
        overrides.refused = overrides.ratio > maxRatio;
        if (overrides.refused) {
            overrides.message = "PM semantics mismatch: decide ORG_PARENT_SOURCE first (" + overrideFor.size() + " of "
                    + matches.size() + " matched rows would need a manager override, more than " + maxRatio + ")";
        }

        // 3. Fill the overlay
        Set<String> seen = new HashSet<>();
        Map<String, Short> nextRank = new HashMap<>();
        Set<String> headsSkipped = new LinkedHashSet<>();
        List<EmployeeOverride> created = new ArrayList<>();
        for (Match match : matches) {
            ExcelRow row = match.row();
            String cs = row.cs();
            String key = key(match.employee().getId(), cs);
            if (!seen.add(key)) {
                report.duplicateRows.add(row.number());
            }
            EmployeeOverride target = rows.get(key);
            boolean isNew = target == null;
            if (isNew) {
                target = EmployeeOverride.builder().employeeId(match.employee().getId()).cs(cs).build();
                rows.put(key, target);
                created.add(target);
            } else if (target.getUpdatedBy() != null && !IMPORTED_BY.equals(target.getUpdatedBy())) {
                report.rowsEditedByOthers.add(row.number());
                continue;
            }

            boolean filled = false;
            if (!match.head()) {
                filled |= fill(target::getGroupName, target::setGroupName,
                        clean(row, content, ExcelOverlaySheet.GROUP, row.group(), OverrideAdminService.GROUP_MAX, report),
                        "groupName", report);
            }
            filled |= fill(target::getAreaOfDuties, target::setAreaOfDuties,
                    clean(row, content, ExcelOverlaySheet.AREA, row.areaOfDuties(), OverrideAdminService.TEXT_MAX, report),
                    "areaOfDuties", report);
            filled |= fill(target::getJobDescription, target::setJobDescription,
                    clean(row, content, ExcelOverlaySheet.DESCRIPTION, row.jobDescription(), OverrideAdminService.TEXT_MAX,
                            report), "jobDescription", report);
            if (match.head()) {
                if (functionsWithHeads.contains(cs)) {
                    headsSkipped.add(cs);
                } else if (target.getHeadRank() == null) {
                    short rank = nextRank.merge(cs, (short) 1, (a, b) -> (short) (a + b));
                    target.setHeadRank(rank);
                    report.headsSet.merge(cs, 1, Integer::sum);
                    report.fill("headRank");
                    filled = true;
                }
            }
            Long managerOverride = overrideFor.get(row);
            if (managerOverride != null && !overrides.refused && target.getManagerOverrideId() == null) {
                target.setManagerOverrideId(managerOverride);
                overrides.created++;
                report.fill("managerOverride");
                filled = true;
            }

            if (isNew) {
                report.rowsCreated++;
                target.setUpdatedBy(IMPORTED_BY);
            } else if (filled) {
                report.rowsFilled++;
                target.setUpdatedBy(IMPORTED_BY);
            } else {
                report.rowsUnchanged++;
            }

            checkTitle(match, report);
            countDefaultGroup(match, content, resolver, report);
        }
        report.headsSkippedFunctions.addAll(headsSkipped);
        report.defaultGroups.percent = report.defaultGroups.rowsWithGroup == 0 ? 0
                : round(100.0 * report.defaultGroups.equalToDefaultGroup / report.defaultGroups.rowsWithGroup, 1);

        // 4. Central Services employees no row matched, per function
        for (Employee e : all) {
            if (e.isEnabled() && !e.isExternal() && !inExcel.contains(e.getId())) {
                String cs = resolver.resolve(levels(e)).csName();
                report.adEmployeesNotInExcelByFunction.merge(cs == null ? "(no function)" : cs, 1, Integer::sum);
            }
        }

        if (!dryRun) {
            // U4: the group names of the Excel file are groups of their function (made as they are when missing)
            for (EmployeeOverride target : rows.values()) {
                if (target.getGroupName() != null && target.getGroupId() == null) {
                    FunctionGroup group = groups.resolveOrCreate(target.getCs(), target.getGroupName(),
                            FunctionGroup.MANUAL, IMPORTED_BY);
                    target.setGroupId(group.getId());
                    target.setGroupName(group.getName());
                }
            }
            overlay.saveAll(created);
        }
        return report.rowsCreated + report.rowsFilled > 0;
    }

    /** The rules of admin edits; a refused value is reported (row and column) and not stored */
    private static String clean(ExcelRow row, ExcelOverlaySheet.Content content, String column, String value, int max,
                                ImportReport report) {
        try {
            return OverrideAdminService.clean(column, value, max);
        } catch (AdminEditException e) {
            report.rejectedValues.add(new ImportReport.Rejected(row.number(),
                    content.headers().getOrDefault(column, column), e.getCode()));
            return null;
        }
    }

    private static boolean fill(Supplier<String> get, Consumer<String> set, String value, String field,
                                ImportReport report) {
        if (value == null || get.get() != null) {
            return false;
        }
        set.accept(value);
        report.fill(field);
        return true;
    }

    /** An Excel title ending with ", XX" (country head) whose AD title does not end the same way */
    private static void checkTitle(Match match, ImportReport report) {
        String excelTitle = match.row().jobTitle();
        Matcher suffix = excelTitle == null ? null : TITLE_SUFFIX.matcher(excelTitle);
        if (suffix == null || !suffix.find()) {
            return;
        }
        String adTitle = match.employee().getJobTitle();
        Matcher ad = adTitle == null ? null : TITLE_SUFFIX.matcher(adTitle.trim());
        if (ad == null || !ad.find() || !ad.group(1).equalsIgnoreCase(suffix.group(1))) {
            report.titleSuffixMismatchRows.add(match.row().number());
        }
    }

    private static void countDefaultGroup(Match match, ExcelOverlaySheet.Content content, FunctionResolver resolver,
                                          ImportReport report) {
        String group = match.row().group();
        if (match.head() || group == null) {
            return;
        }
        report.defaultGroups.rowsWithGroup++;
        if (group.equalsIgnoreCase(Objects.toString(resolver.defaultGroup(levels(match.employee())), ""))) {
            report.defaultGroups.equalToDefaultGroup++;
        }
    }

    private static NormalizedDepartment levels(Employee e) {
        return new NormalizedDepartment(e.getDeptLevel1(), e.getDeptLevel2(), e.getDeptLevel3(), e.getDeptLevel4(),
                List.of());
    }

    private static String key(Long employeeId, String cs) {
        return employeeId + "\u0000" + cs;
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    /** How an address was matched; ambiguous: several employees share the local part */
    record Lookup(Employee employee, String how, boolean ambiguous) {
        static final Lookup NONE = new Lookup(null, "NONE", false);
        static final Lookup AMBIGUOUS = new Lookup(null, "NONE", true);
    }

    /**
     * Employees by lower(e-mail), lower(UPN) and local part. Where several rows share a key, enabled Central
     * Services employees win (an account created again in AD leaves its old row behind).
     */
    static final class Index {

        private final Map<String, Employee> byEmail = new HashMap<>();
        private final Map<String, Employee> byUpn = new HashMap<>();
        private final Map<String, Map<Long, Employee>> byLocalPart = new LinkedHashMap<>();

        Index(List<Employee> all) {
            for (Employee e : all) {
                put(byEmail, e.getEmail(), e);
                put(byUpn, e.getUpn(), e);
                for (String address : new String[]{e.getEmail(), e.getUpn()}) {
                    if (address != null) {
                        byLocalPart.computeIfAbsent(localPart(address), k -> new LinkedHashMap<>()).put(e.getId(), e);
                    }
                }
            }
        }

        Lookup find(String address) {
            String key = lower(address);
            Employee e = byEmail.get(key);
            if (e != null) {
                return new Lookup(e, "EMAIL", false);
            }
            e = byUpn.get(key);
            if (e != null) {
                return new Lookup(e, "UPN", false);
            }
            Map<Long, Employee> sameLocalPart = byLocalPart.getOrDefault(localPart(key), Map.of());
            List<Employee> candidates = new ArrayList<>(sameLocalPart.values());
            if (candidates.size() > 1) {
                candidates.removeIf(c -> !c.isEnabled() || c.isExternal());
            }
            if (candidates.size() == 1) {
                return new Lookup(candidates.get(0), "LOCAL_PART", false);
            }
            return sameLocalPart.size() > 1 ? Lookup.AMBIGUOUS : Lookup.NONE;
        }

        private static void put(Map<String, Employee> map, String address, Employee e) {
            if (address == null) {
                return;
            }
            map.merge(lower(address), e, (old, candidate) -> preferred(candidate) && !preferred(old) ? candidate : old);
        }

        private static boolean preferred(Employee e) {
            return e.isEnabled() && !e.isExternal();
        }

        private static String localPart(String address) {
            String lower = lower(address);
            int at = lower.indexOf('@');
            return at < 0 ? lower : lower.substring(0, at);
        }

        private static String lower(String value) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
