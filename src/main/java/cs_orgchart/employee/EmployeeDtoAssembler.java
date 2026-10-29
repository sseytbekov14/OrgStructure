package cs_orgchart.employee;

import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.employee.EmployeeSourceProperties.ParentSource;
import cs_orgchart.employee.group.FunctionGroup;
import cs_orgchart.employee.override.EmployeeOverride;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Database employees + overlay → the employees of the site (spec v3 §10.1, amended: of the new fields only id,
 * parentId and countryCode; phone, mobile and office are not shown).
 * <ul>
 *   <li>only enabled employees from Central Services (not external);</li>
 *   <li>a person without overlay rows: once, in the function of their AD department (§5.5), default group;</li>
 *   <li>a person with overlay rows: once per row, with its function, group (HEAD for a head) and texts;</li>
 *   <li>parent: the manager override of the row, else the ORG_PARENT_SOURCE relation (pm_id or manager_id) with the
 *       other one as fallback; a disabled parent counts as none. External parents appear only in pm, pmEmail,
 *       pmJobTitle and pmPhotoUrl; parentId only points to a person on the site;</li>
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

    /**
     * Where AD alone puts a person (spec §5.5): the site function (null: none or hidden) and the default group in it
     */
    public record AdPlacement(String cs, String defaultGroup) {
    }

    public AdPlacement adPlacement(Employee employee) {
        NormalizedDepartment department = new NormalizedDepartment(employee.getDeptLevel1(),
                employee.getDeptLevel2(), employee.getDeptLevel3(), employee.getDeptLevel4(), List.of());
        FunctionResolver.Resolution adFunction = functions.resolve(department);
        String cs = adFunction.csName() != null && adFunction.visible() ? adFunction.csName() : null;
        return new AdPlacement(cs, cs == null ? null : functions.defaultGroup(department));
    }

    /** What one build knows besides the employees: groups by id and by (function, name), hidden people */
    private record Build(Map<Long, Employee> byId, Map<Long, FunctionGroup> groupsById,
                         Map<String, FunctionGroup> groupsByName, Set<Long> hidden) {

        FunctionGroup groupNamed(String cs, String name) {
            return name == null ? null : groupsByName.get(nameKey(cs, name));
        }
    }

    private static String nameKey(String cs, String name) {
        return cs + "\n" + name.toLowerCase(Locale.ROOT);
    }

    public List<cs_orgchart.model.Employee> assemble(List<Employee> employees, List<EmployeeOverride> overlay,
                                                     Map<String, String> photoFiles) {
        return assemble(employees, overlay, List.of(), Set.of(), photoFiles);
    }

    /**
     * U4: the group of a row is its group object (group_id; group_name is no longer read), else the AD default
     * group in the AD function; groupOrder is the sort order of that group. Hidden people are left out; whoever
     * reports to them gets the next visible manager up the AD chain (else none: the root).
     */
    public List<cs_orgchart.model.Employee> assemble(List<Employee> employees, List<EmployeeOverride> overlay,
                                                     List<FunctionGroup> groups, Set<Long> hidden,
                                                     Map<String, String> photoFiles) {
        return assemble(employees, overlay, groups, hidden, PhotoLinks.folderOnly(photoFiles));
    }

    /** With the photo URLs of {@link PhotoLinks} (the stored AD photos and the folder, in their priority) */
    public List<cs_orgchart.model.Employee> assemble(List<Employee> employees, List<EmployeeOverride> overlay,
                                                     List<FunctionGroup> groups, Set<Long> hidden,
                                                     PhotoLinks photoFiles) {
        Map<Long, Employee> byId = new HashMap<>();
        employees.forEach(e -> byId.put(e.getId(), e));
        Map<Long, List<EmployeeOverride>> overlayByEmployee = new HashMap<>();
        overlay.forEach(o -> overlayByEmployee.computeIfAbsent(o.getEmployeeId(), k -> new ArrayList<>()).add(o));
        Map<Long, FunctionGroup> groupsById = new HashMap<>();
        Map<String, FunctionGroup> groupsByName = new HashMap<>();
        groups.forEach(g -> {
            groupsById.put(g.getId(), g);
            groupsByName.put(nameKey(g.getCs(), g.getName()), g);
        });
        Build build = new Build(byId, groupsById, groupsByName, hidden);

        List<Row> rows = new ArrayList<>();
        int withoutFunction = 0;
        for (Employee employee : employees) {
            if (!employee.isEnabled() || employee.isExternal() || hidden.contains(employee.getId())) {
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
                FunctionGroup auto = build.groupNamed(adFunction.csName(), defaultGroup);
                rows.add(new Row(dto(employee, adFunction.csName(), auto != null ? auto.getName() : defaultGroup,
                        auto, null, build, photoFiles), null));
            } else {
                for (EmployeeOverride membership : memberships) {
                    FunctionGroup group = membership.getHeadRank() != null ? null
                            : membership.getGroupId() != null ? groupsById.get(membership.getGroupId())
                            : membership.getCs().equals(adFunction.csName())
                            ? build.groupNamed(membership.getCs(), defaultGroup) : null;
                    String groupName = membership.getHeadRank() != null ? HEAD
                            : group != null ? group.getName()
                            : membership.getGroupId() == null && membership.getCs().equals(adFunction.csName())
                            ? defaultGroup : null;
                    rows.add(new Row(dto(employee, membership.getCs(), groupName, group, membership, build,
                            photoFiles), membership.getHeadRank()));
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

    private cs_orgchart.model.Employee dto(Employee employee, String cs, String group, FunctionGroup groupObject,
                                           EmployeeOverride membership, Build build, PhotoLinks photoFiles) {
        String email = employee.getEmail() != null ? employee.getEmail() : employee.getUpn();
        Employee parent = visibleParent(employee, membership, build);
        String parentEmail = parent == null ? null : Objects.requireNonNullElse(parent.getEmail(), parent.getUpn());
        return cs_orgchart.model.Employee.builder()
                .name(employee.getDisplayName())
                .cs(cs)
                .group(group)
                .jobTitle(employee.getJobTitle())
                .email(email)
                .pm(parent == null ? null : parent.getDisplayName())
                .pmEmail(parentEmail)
                .pmJobTitle(parent == null ? null : parent.getJobTitle())
                .photoUrl(photoFiles.urlFor(employee.getId(), email))
                .gradeOrder(GradeOrder.of(employee.getJobTitle()))
                .areaOfDuties(membership == null ? null : membership.getAreaOfDuties())
                .jobDescription(membership == null ? null : membership.getJobDescription())
                .id(employee.getId())
                .parentId(parent != null && !parent.isExternal() ? parent.getId() : null)
                .countryCode(employee.getCountryCode())
                .groupOrder(groupObject == null ? null : groupObject.getSortOrder())
                .city(employee.getCity())
                .country(country(employee))
                .pmId(parent == null ? null : parent.getId())
                .pmExternal(parent == null ? null : parent.isExternal())
                .pmPhotoUrl(parent != null && parent.isExternal()
                        ? photoFiles.urlFor(parent.getId(), parentEmail) : null)
                .build();
    }

    /** U6: AD co, else the English name of the two-letter code of AD c, else null */
    public static String country(Employee employee) {
        if (employee.getCountryName() != null && !employee.getCountryName().isBlank()) {
            return employee.getCountryName();
        }
        String iso = employee.getCountryIso();
        if (iso == null || iso.length() != 2) {
            return null;
        }
        String name = Locale.of("", iso).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() || name.equalsIgnoreCase(iso) ? null : name;
    }

    /** The effective parent, or for a hidden one the next visible manager up the AD chain (U4); null: none */
    private Employee visibleParent(Employee employee, EmployeeOverride membership, Build build) {
        Employee parent = parent(employee, membership, build.byId());
        Set<Long> seen = new HashSet<>();
        while (parent != null && build.hidden().contains(parent.getId()) && seen.add(parent.getId())) {
            parent = adParent(parent, build.byId(), parentSource);
        }
        if (parent == null || build.hidden().contains(parent.getId()) || parent.getId().equals(employee.getId())) {
            return null;
        }
        return parent;
    }

    /** The effective parent (spec §10.1), or null */
    private Employee parent(Employee employee, EmployeeOverride membership, Map<Long, Employee> byId) {
        if (membership != null && membership.getManagerOverrideId() != null) {
            Employee override = usable(employee, byId.get(membership.getManagerOverrideId()));
            if (override != null) {
                return override;
            }
        }
        return adParent(employee, byId, parentSource);
    }

    /**
     * The parent from AD without overrides: the ORG_PARENT_SOURCE relation, else the other one; a disabled parent
     * counts as none. Also used by the Excel importer to decide where a manager override is needed.
     */
    public static Employee adParent(Employee employee, Map<Long, Employee> byId, ParentSource parentSource) {
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
