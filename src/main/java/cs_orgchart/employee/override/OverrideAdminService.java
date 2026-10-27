package cs_orgchart.employee.override;

import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.EmployeeSourceProperties;
import cs_orgchart.employee.override.OverrideAdminViews.HeadView;
import cs_orgchart.employee.override.OverrideAdminViews.HeadsRequest;
import cs_orgchart.employee.override.OverrideAdminViews.HeadsView;
import cs_orgchart.employee.override.OverrideAdminViews.MemberView;
import cs_orgchart.employee.override.OverrideAdminViews.OverrideRequest;
import cs_orgchart.employee.override.OverrideAdminViews.OverrideView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Admin editing of the overlay (spec v3 §10.2): group, area of duties and job description of a person in a function,
 * and the heads of a function. Only with EMPLOYEE_SOURCE=db. Every change is one transaction; the employee directory
 * is rebuilt after it committed ({@link OverlayChanged}). The log names the admin, the employee id, the function and
 * the changed field names, never the texts.
 */
@Slf4j
@Service
public class OverrideAdminService {

    public static final int GROUP_MAX = 256;
    public static final int TEXT_MAX = 500;

    private final EmployeeRepository employees;
    private final EmployeeOverrideRepository overlay;
    private final DepartmentFunctionMapRepository functionMap;
    private final EmployeeDirectory directory;
    private final EmployeeSourceProperties source;
    private final ApplicationEventPublisher events;

    public OverrideAdminService(EmployeeRepository employees, EmployeeOverrideRepository overlay,
                                DepartmentFunctionMapRepository functionMap, EmployeeDirectory directory,
                                EmployeeSourceProperties source, ApplicationEventPublisher events) {
        this.employees = employees;
        this.overlay = overlay;
        this.functionMap = functionMap;
        this.directory = directory;
        this.source = source;
        this.events = events;
    }

    public boolean editingEnabled() {
        return source.isDb();
    }

    // --- read ----------------------------------------------------------------------------------------------------

    /**
     * The people of a function (cs) with their overlay row, or with cs null all people of the site (one row each);
     * q filters by name, e-mail and job title.
     */
    @Transactional(readOnly = true)
    public List<MemberView> members(String cs, String q) {
        requireEditing();
        if (cs != null) {
            requireKnownFunction(cs);
        }
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<Long, EmployeeOverride> rowsOfFunction = new HashMap<>();
        if (cs != null) {
            overlay.findByCs(cs).forEach(o -> rowsOfFunction.put(o.getEmployeeId(), o));
        }
        Set<Long> seen = new HashSet<>();
        List<MemberView> members = new ArrayList<>();
        for (cs_orgchart.model.Employee e : directory.getAllEmployees()) {
            if (e.getId() == null || (cs != null ? !cs.equals(e.getCs()) : !seen.add(e.getId()))) {
                continue;
            }
            if (!query.isEmpty() && !contains(e.getName(), query) && !contains(e.getEmail(), query)
                    && !contains(e.getJobTitle(), query)) {
                continue;
            }
            EmployeeOverride row = rowsOfFunction.get(e.getId());
            members.add(new MemberView(e.getId(), e.getName(), e.getEmail(), e.getJobTitle(), e.getCountryCode(),
                    e.getCs(), e.getGroup(), row == null ? null : OverrideView.of(row)));
        }
        return members;
    }

    @Transactional(readOnly = true)
    public HeadsView heads(String cs) {
        requireEditing();
        requireKnownFunction(cs);
        return headsView(cs);
    }

    // --- texts ---------------------------------------------------------------------------------------------------

    /** Creates or updates the overlay row of the person in the function (optimistic lock on the version) */
    @Transactional
    public OverrideView upsert(Long employeeId, String cs, OverrideRequest request, String admin) {
        requireEditing();
        requireKnownFunction(cs);
        requireListedEmployee(employeeId);
        if (request == null) {
            throw AdminEditException.badRequest("BODY_MISSING", "the fields are missing");
        }
        String groupName = clean("groupName", request.groupName(), GROUP_MAX);
        String areaOfDuties = clean("areaOfDuties", request.areaOfDuties(), TEXT_MAX);
        String jobDescription = clean("jobDescription", request.jobDescription(), TEXT_MAX);

        EmployeeOverride row = overlay.findByEmployeeIdAndCs(employeeId, cs).orElse(null);
        boolean created = row == null;
        if (created) {
            if (request.version() != null) {
                throw AdminEditException.conflict("CHANGED", "the entry was removed by someone else meanwhile;"
                        + " reload the page");
            }
            row = EmployeeOverride.builder().employeeId(employeeId).cs(cs).build();
        } else if (!Objects.equals(request.version(), row.getVersion())) {
            throw AdminEditException.conflict("CHANGED", "someone else changed this entry meanwhile; reload the"
                    + " page and edit again");
        }
        List<String> changed = new ArrayList<>();
        set(row::getGroupName, row::setGroupName, groupName, "groupName", changed);
        set(row::getAreaOfDuties, row::setAreaOfDuties, areaOfDuties, "areaOfDuties", changed);
        set(row::getJobDescription, row::setJobDescription, jobDescription, "jobDescription", changed);
        if (!created && changed.isEmpty()) {
            return OverrideView.of(row);
        }
        row.setUpdatedBy(admin);
        EmployeeOverride saved = overlay.saveAndFlush(row);
        log.info("Admin {} {} the overlay of employee {} in {}: {}", admin, created ? "created" : "updated",
                employeeId, cs, changed);
        events.publishEvent(new OverlayChanged(cs));
        return OverrideView.of(saved);
    }

    /**
     * Removes the overlay row: the person is back to the AD default in their own function, or leaves an extra
     * function. Removing the last head of a function needs {@code confirmLastHead}.
     */
    @Transactional
    public void delete(Long employeeId, String cs, Long version, boolean confirmLastHead, String admin) {
        requireEditing();
        requireKnownFunction(cs);
        EmployeeOverride row = overlay.findByEmployeeIdAndCs(employeeId, cs)
                .orElseThrow(() -> AdminEditException.notFound("NOT_FOUND", "no overlay entry for employee "
                        + employeeId + " in " + cs));
        if (version != null && !version.equals(row.getVersion())) {
            throw AdminEditException.conflict("CHANGED", "someone else changed this entry meanwhile; reload the page");
        }
        if (row.getHeadRank() != null && visibleHeads(cs).size() == 1 && isVisible(row) && !confirmLastHead) {
            throw AdminEditException.conflict("LAST_HEAD", "this is the last head of " + cs + "; confirm to leave"
                    + " the function without a head");
        }
        overlay.delete(row);
        overlay.flush();
        log.info("Admin {} removed the overlay of employee {} in {}", admin, employeeId, cs);
        events.publishEvent(new OverlayChanged(cs));
    }

    // --- heads ---------------------------------------------------------------------------------------------------

    /**
     * The heads of a function in the given order (spec §10.2). Missing overlay rows are created; a person no longer
     * in the list keeps their row as a normal member. Heads disabled in AD are kept after the new ones. Refused with
     * 409, and nothing changed, when the heads differ from {@code expectedHeads}.
     */
    @Transactional
    public HeadsView setHeads(String cs, HeadsRequest request, String admin) {
        requireEditing();
        requireKnownFunction(cs);
        if (request == null || request.employeeIds() == null || request.expectedHeads() == null) {
            throw AdminEditException.badRequest("BODY_MISSING", "employeeIds and expectedHeads are required");
        }
        List<Long> wanted = request.employeeIds();
        if (new HashSet<>(wanted).size() != wanted.size() || wanted.contains(null)) {
            throw AdminEditException.badRequest("DUPLICATE", "every head may be listed once");
        }
        wanted.forEach(this::requireListedEmployee);

        List<Long> current = visibleHeads(cs).stream().map(EmployeeOverride::getEmployeeId).toList();
        if (!current.equals(request.expectedHeads())) {
            throw AdminEditException.conflict("HEADS_CHANGED", "the heads of " + cs + " were changed meanwhile;"
                    + " reload the page");
        }
        if (wanted.isEmpty() && !current.isEmpty() && !request.confirmRemoveAllHeads()) {
            throw AdminEditException.conflict("LAST_HEAD", "confirm to leave " + cs + " without a head");
        }

        Map<Long, EmployeeOverride> rows = new LinkedHashMap<>();
        overlay.findByCs(cs).forEach(o -> rows.put(o.getEmployeeId(), o));
        List<EmployeeOverride> hiddenHeads = rows.values().stream()
                .filter(o -> o.getHeadRank() != null && !isVisible(o) && !wanted.contains(o.getEmployeeId()))
                .sorted(Comparator.comparing(EmployeeOverride::getHeadRank)).toList();

        // Ranks of the function cleared first, so that the new ones never meet an old one (partial unique index)
        for (EmployeeOverride row : rows.values()) {
            if (row.getHeadRank() != null) {
                row.setHeadRank(null);
                row.setUpdatedBy(admin);
            }
        }
        overlay.saveAllAndFlush(rows.values());

        short rank = 1;
        for (Long employeeId : wanted) {
            EmployeeOverride row = rows.computeIfAbsent(employeeId,
                    id -> EmployeeOverride.builder().employeeId(id).cs(cs).build());
            row.setHeadRank(rank++);
            row.setUpdatedBy(admin);
        }
        for (EmployeeOverride hidden : hiddenHeads) {
            hidden.setHeadRank(rank++);
        }
        overlay.saveAllAndFlush(rows.values());
        log.info("Admin {} set the heads of {}: employees {} (before {})", admin, cs, wanted, current);
        events.publishEvent(new OverlayChanged(cs));
        return headsView(cs);
    }

    // --- checks --------------------------------------------------------------------------------------------------

    private void requireEditing() {
        if (!editingEnabled()) {
            throw AdminEditException.conflict("EDITING_DISABLED", "admin editing requires EMPLOYEE_SOURCE=db");
        }
    }

    private void requireKnownFunction(String cs) {
        if (cs == null || cs.isBlank() || !functionMap.findCsNames().contains(cs)) {
            throw AdminEditException.badRequest("UNKNOWN_FUNCTION", "unknown function: " + cs);
        }
    }

    /** The person exists, is enabled and from Central Services (not an external manager) */
    private Employee requireListedEmployee(Long employeeId) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> AdminEditException.notFound("UNKNOWN_EMPLOYEE", "no employee " + employeeId));
        if (!employee.isEnabled()) {
            throw AdminEditException.badRequest("EMPLOYEE_DISABLED", "employee " + employeeId + " is disabled in AD");
        }
        if (employee.isExternal()) {
            throw AdminEditException.badRequest("EMPLOYEE_EXTERNAL", "employee " + employeeId + " is not in Central"
                    + " Services");
        }
        return employee;
    }

    /** Trimmed, empty → null, at most {@code max} characters, no HTML (admin edits and the Excel import) */
    public static String clean(String field, String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.replace(' ', ' ').trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > max) {
            throw AdminEditException.badRequest("TOO_LONG", field + " has more than " + max + " characters");
        }
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            throw AdminEditException.badRequest("HTML", field + " must not contain < or >");
        }
        return text;
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private HeadsView headsView(String cs) {
        List<EmployeeOverride> all = overlay.findByCs(cs).stream().filter(o -> o.getHeadRank() != null)
                .sorted(Comparator.comparing(EmployeeOverride::getHeadRank)).toList();
        List<HeadView> visible = new ArrayList<>();
        int hidden = 0;
        int rank = 1;
        for (EmployeeOverride head : all) {
            Employee e = employees.findById(head.getEmployeeId()).orElse(null);
            if (e == null || !e.isEnabled() || e.isExternal()) {
                hidden++;
            } else {
                visible.add(new HeadView(e.getId(), e.getDisplayName(), e.getJobTitle(), rank++));
            }
        }
        return new HeadsView(cs, visible, hidden);
    }

    /** The heads of the function shown on the site, by rank */
    private List<EmployeeOverride> visibleHeads(String cs) {
        return overlay.findByCs(cs).stream().filter(o -> o.getHeadRank() != null).filter(this::isVisible)
                .sorted(Comparator.comparing(EmployeeOverride::getHeadRank)).toList();
    }

    private boolean isVisible(EmployeeOverride row) {
        return employees.findById(row.getEmployeeId()).map(e -> e.isEnabled() && !e.isExternal()).orElse(false);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static void set(Supplier<String> get, Consumer<String> set, String value, String field,
                            List<String> changed) {
        if (!Objects.equals(get.get(), value)) {
            set.accept(value);
            changed.add(field);
        }
    }
}
