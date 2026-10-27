package cs_orgchart.employee;

import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.employee.EmployeeSourceProperties.ParentSource;
import cs_orgchart.employee.override.EmployeeOverride;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Database employees + overlay → the employees of the site (spec v3 §10.1, amended: of the new fields only id,
 * parentId and countryCode; phone, mobile and office are not shown).
 * <ul>
 *   <li>only enabled employees from Central Services (not external);</li>
 *   <li>a person without overlay rows: once, in the function of their AD department (§5.5), default group;</li>
 *   <li>a person with overlay rows: once per row, with its function, group (HEAD for a head) and texts;</li>
 *   <li>parent: the manager override of the row, else the ORG_PARENT_SOURCE relation (pm_id or manager_id) with the
 *       other one as fallback; a disabled parent counts as none. External parents appear only in pm, pmEmail and
 *       pmJobTitle; parentId only points to a person on the site;</li>
 *   <li>order: by function, the heads first by head_rank, then by grade, job title and name.</li>
 * </ul>
 */
@Slf4j
public class EmployeeDtoAssembler {

    public static final String HEAD = "HEAD";

    private final FunctionResolver functions;
    private final ParentSource parentSource;

    public EmployeeDtoAssembler(FunctionResolver functions, ParentSource parentSource) {
        this.functions = functions;
        this.parentSource = parentSource;
    }

    private record Row(cs_orgchart.model.Employee dto, Short headRank) {
    }

    public List<cs_orgchart.model.Employee> assemble(List<Employee> employees, List<EmployeeOverride> overlay,
                                                     Map<String, String> photoFiles) {
        Map<Long, Employee> byId = new HashMap<>();
        employees.forEach(e -> byId.put(e.getId(), e));
        Map<Long, List<EmployeeOverride>> overlayByEmployee = new HashMap<>();
        overlay.forEach(o -> overlayByEmployee.computeIfAbsent(o.getEmployeeId(), k -> new ArrayList<>()).add(o));

        List<Row> rows = new ArrayList<>();
        int withoutFunction = 0;
        for (Employee employee : employees) {
            if (!employee.isEnabled() || employee.isExternal()) {
                continue;
            }
            NormalizedDepartment department = new NormalizedDepartment(employee.getDeptLevel1(),
                    employee.getDeptLevel2(), employee.getDeptLevel3(), employee.getDeptLevel4(), List.of());
            FunctionResolver.Resolution adFunction = functions.resolve(department);
            String defaultGroup = functions.defaultGroup(department);
            List<EmployeeOverride> memberships = overlayByEmployee.getOrDefault(employee.getId(), List.of());

            if (memberships.isEmpty()) {
                if (adFunction.csName() == null || !adFunction.visible()) {
                    withoutFunction++;
                    continue;
                }
                rows.add(new Row(dto(employee, adFunction.csName(), defaultGroup, null, byId, photoFiles), null));
            } else {
                for (EmployeeOverride membership : memberships) {
                    String group = membership.getHeadRank() != null ? HEAD
                            : membership.getGroupName() != null ? membership.getGroupName()
                            : membership.getCs().equals(adFunction.csName()) ? defaultGroup : null;
                    rows.add(new Row(dto(employee, membership.getCs(), group, membership, byId, photoFiles),
                            membership.getHeadRank()));
                }
            }
        }
        if (withoutFunction > 0) {
            log.warn("Employee directory: {} employee(s) without a site function (no department level 2 or a hidden"
                    + " function) are not shown", withoutFunction);
        }

        rows.sort(Comparator.comparing((Row r) -> r.dto().getCs(), Comparator.nullsLast(String::compareTo))
                .thenComparing(r -> r.headRank() == null)
                .thenComparing(Row::headRank, Comparator.nullsLast(Short::compareTo))
                .thenComparingInt(r -> r.dto().getGradeOrder())
                .thenComparing(r -> r.dto().getJobTitle(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> r.dto().getName(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> r.dto().getId()));
        return rows.stream().map(Row::dto).toList();
    }

    private cs_orgchart.model.Employee dto(Employee employee, String cs, String group, EmployeeOverride membership,
                                           Map<Long, Employee> byId, Map<String, String> photoFiles) {
        String email = employee.getEmail() != null ? employee.getEmail() : employee.getUpn();
        Employee parent = parent(employee, membership, byId);
        return cs_orgchart.model.Employee.builder()
                .name(employee.getDisplayName())
                .cs(cs)
                .group(group)
                .jobTitle(employee.getJobTitle())
                .email(email)
                .pm(parent == null ? null : parent.getDisplayName())
                .pmEmail(parent == null ? null : Objects.requireNonNullElse(parent.getEmail(), parent.getUpn()))
                .pmJobTitle(parent == null ? null : parent.getJobTitle())
                .photoUrl(EmployeePhotos.urlFor(email, photoFiles))
                .gradeOrder(GradeOrder.of(employee.getJobTitle()))
                .areaOfDuties(membership == null ? null : membership.getAreaOfDuties())
                .jobDescription(membership == null ? null : membership.getJobDescription())
                .id(employee.getId())
                .parentId(parent != null && !parent.isExternal() ? parent.getId() : null)
                .countryCode(employee.getCountryCode())
                .build();
    }

    /** The effective parent (spec §10.1), or null */
    private Employee parent(Employee employee, EmployeeOverride membership, Map<Long, Employee> byId) {
        if (membership != null && membership.getManagerOverrideId() != null) {
            Employee override = usable(employee, byId.get(membership.getManagerOverrideId()));
            if (override != null) {
                return override;
            }
        }
        Long first = parentSource == ParentSource.PM ? employee.getPmId() : employee.getManagerId();
        Long fallback = parentSource == ParentSource.PM ? employee.getManagerId() : employee.getPmId();
        Employee parent = usable(employee, first == null ? null : byId.get(first));
        return parent != null ? parent : usable(employee, fallback == null ? null : byId.get(fallback));
    }

    /** A parent that can be shown: enabled and not the person themselves */
    private static Employee usable(Employee employee, Employee parent) {
        return parent != null && parent.isEnabled() && !parent.getId().equals(employee.getId()) ? parent : null;
    }
}
