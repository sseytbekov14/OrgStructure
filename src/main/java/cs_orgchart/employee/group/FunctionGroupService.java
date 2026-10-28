package cs_orgchart.employee.group;

import cs_orgchart.employee.DbEmployeeDirectory;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.employee.EmployeeDtoAssembler;
import cs_orgchart.employee.EmployeeDtoAssembler.AdPlacement;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.group.GroupViews.CreateGroupRequest;
import cs_orgchart.employee.group.GroupViews.GroupView;
import cs_orgchart.employee.group.GroupViews.GroupsView;
import cs_orgchart.employee.group.GroupViews.MemberRef;
import cs_orgchart.employee.group.GroupViews.MembersRequest;
import cs_orgchart.employee.group.GroupViews.MembersResult;
import cs_orgchart.employee.group.GroupViews.Moved;
import cs_orgchart.employee.group.GroupViews.UpdateGroupRequest;
import cs_orgchart.employee.override.AdminEditException;
import cs_orgchart.employee.override.AdminGuards;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.override.FieldRules;
import cs_orgchart.employee.override.OverlayChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groups of a function (U4): list, create, rename / reorder / (de)activate, delete when empty, add or move people,
 * take them out of a group. A person has one group per function (employee_overrides.group_id); without one, the AD
 * default group applies in their own function. Moving someone into their AD default group stores no manual group.
 * Every change is one transaction; the directory is rebuilt after it committed. The log names the admin, ids and
 * field names only.
 */
@Slf4j
@Service
public class FunctionGroupService {

    /** The country codes that are columns of their own (as COUNTRY_CODES of function.js) */
    public static final Set<String> COUNTRY_CODES = Set.of("KZ", "UZ", "AZ", "GE", "AM", "KG", "RU");
    static final int MAX_MEMBERS_PER_REQUEST = 50;
    private static final Set<String> RESERVED_TEAM_NAMES = Set.of("head", "other");

    private final FunctionGroupRepository groups;
    private final EmployeeOverrideRepository overlay;
    private final EmployeeRepository employees;
    private final EmployeeDirectory directory;
    private final ObjectProvider<DbEmployeeDirectory> dbDirectory;
    private final AdminGuards guards;
    private final ApplicationEventPublisher events;

    public FunctionGroupService(FunctionGroupRepository groups, EmployeeOverrideRepository overlay,
                                EmployeeRepository employees, EmployeeDirectory directory,
                                ObjectProvider<DbEmployeeDirectory> dbDirectory, AdminGuards guards,
                                ApplicationEventPublisher events) {
        this.groups = groups;
        this.overlay = overlay;
        this.employees = employees;
        this.directory = directory;
        this.dbDirectory = dbDirectory;
        this.guards = guards;
        this.events = events;
    }

    // --- read ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public GroupsView list(String cs) {
        guards.requireEditing();
        guards.requireKnownFunction(cs);
        List<FunctionGroup> ofFunction = groups.findByCsOrderBySortOrderAscNameAsc(cs);
        Map<String, FunctionGroup> byName = new HashMap<>();
        ofFunction.forEach(g -> byName.put(key(g.getName()), g));
        Map<Long, Long> manualGroupOf = new HashMap<>();
        overlay.findByCs(cs).forEach(o -> {
            if (o.getGroupId() != null) {
                manualGroupOf.put(o.getEmployeeId(), o.getGroupId());
            }
        });

        Map<Long, List<MemberRef>> members = new LinkedHashMap<>();
        ofFunction.forEach(g -> members.put(g.getId(), new ArrayList<>()));
        List<MemberRef> heads = new ArrayList<>();
        List<MemberRef> ungrouped = new ArrayList<>();
        for (cs_orgchart.model.Employee e : directory.getAllEmployees()) {
            if (e.getId() == null || !cs.equals(e.getCs())) {
                continue;
            }
            if (EmployeeDtoAssembler.HEAD.equals(e.getGroup())) {
                heads.add(ref(e, null));
                continue;
            }
            FunctionGroup group = e.getGroup() == null ? null : byName.get(key(e.getGroup()));
            if (group == null) {
                ungrouped.add(ref(e, null));
                continue;
            }
            String source = Objects.equals(manualGroupOf.get(e.getId()), group.getId()) ? MemberRef.MANUAL : MemberRef.AD;
            members.get(group.getId()).add(ref(e, source));
        }
        List<GroupView> views = ofFunction.stream().map(g -> view(g, members.get(g.getId()))).toList();
        return new GroupsView(cs, views, heads, ungrouped);
    }

    private static MemberRef ref(cs_orgchart.model.Employee e, String source) {
        return new MemberRef(e.getId(), e.getName(), e.getJobTitle(), e.getCountryCode(), source);
    }

    private static GroupView view(FunctionGroup g, List<MemberRef> members) {
        List<MemberRef> list = members == null ? List.of() : members;
        return new GroupView(g.getId(), g.getCs(), g.getName(), g.getKind(), g.getSource(), g.getSortOrder(),
                g.isActive(), g.getVersion(), list.size(), list);
    }

    // --- groups --------------------------------------------------------------------------------------------------

    @Transactional
    public GroupView create(String cs, CreateGroupRequest request, String admin) {
        guards.requireEditing();
        guards.requireKnownFunction(cs);
        if (request == null) {
            throw AdminEditException.badRequest("BODY_MISSING", "the fields are missing");
        }
        String kind = request.kind() == null || request.kind().isBlank() ? FunctionGroup.TEAM
                : request.kind().trim().toUpperCase(Locale.ROOT);
        String name = checkedName(cs, request.name(), kind, null);
        FunctionGroup group = groups.saveAndFlush(FunctionGroup.builder().cs(cs).name(name).kind(kind)
                .source(FunctionGroup.MANUAL).sortOrder(groups.maxSortOrder(cs) + 10).createdBy(admin)
                .updatedBy(admin).build());
        log.info("Admin {} created group {} ({}) in {}", admin, group.getId(), kind, cs);
        events.publishEvent(new OverlayChanged(cs));
        return view(group, List.of());
    }

    /**
     * Rename (not AUTO groups: their name is the AD department), move to a position among the groups of the
     * function (0 = first; the others are renumbered), activate or deactivate (only when empty).
     */
    @Transactional
    public GroupView update(long id, UpdateGroupRequest request, String admin) {
        guards.requireEditing();
        FunctionGroup group = requireGroup(id);
        if (request == null) {
            throw AdminEditException.badRequest("BODY_MISSING", "the fields are missing");
        }
        requireVersion(group, request.version());
        List<String> changed = new ArrayList<>();
        if (request.name() != null && !request.name().equals(group.getName())) {
            String name = checkedName(group.getCs(), request.name(), group.getKind(), group.getId());
            if (!name.equals(group.getName())) {
                if (FunctionGroup.AUTO.equals(group.getSource())) {
                    throw AdminEditException.badRequest("AUTO_GROUP_NAME", "This group comes from Active Directory"
                            + " and keeps its name; create a new group instead.");
                }
                group.setName(name);
                overlay.findByGroupId(group.getId()).forEach(o -> o.setGroupName(name));
                changed.add("name");
            }
        }
        if (request.active() != null && request.active() != group.isActive()) {
            if (!request.active() && memberCount(group) > 0) {
                throw AdminEditException.conflict("NOT_EMPTY", "Move the people out of this group before you"
                        + " deactivate it.");
            }
            group.setActive(request.active());
            changed.add("active");
        }
        if (request.position() != null) {
            List<FunctionGroup> ordered = new ArrayList<>(groups.findByCsOrderBySortOrderAscNameAsc(group.getCs()));
            ordered.removeIf(g -> g.getId().equals(group.getId()));
            int position = Math.max(0, Math.min(request.position(), ordered.size()));
            ordered.add(position, group);
            for (int i = 0; i < ordered.size(); i++) {
                FunctionGroup g = ordered.get(i);
                int order = (i + 1) * 10;
                if (g.getSortOrder() != order) {
                    g.setSortOrder(order);
                    g.setUpdatedBy(admin);
                }
            }
            changed.add("position");
        }
        if (changed.isEmpty()) {
            return view(group, null);
        }
        group.setUpdatedBy(admin);
        FunctionGroup saved = groups.saveAndFlush(group);
        overlay.flush();
        log.info("Admin {} changed group {} in {}: {}", admin, id, group.getCs(), changed);
        events.publishEvent(new OverlayChanged(group.getCs()));
        return view(saved, null);
    }

    /** Only an empty group: no manual member and nobody whose AD default group it is */
    @Transactional
    public void delete(long id, Long version, String admin) {
        guards.requireEditing();
        FunctionGroup group = requireGroup(id);
        requireVersion(group, version);
        if (memberCount(group) > 0) {
            throw AdminEditException.conflict("NOT_EMPTY", "Only an empty group can be deleted: move its people to"
                    + " another group first.");
        }
        overlay.findByGroupId(group.getId()).forEach(o -> {
            o.setGroupId(null);
            o.setGroupName(null);
        });
        overlay.flush();
        groups.delete(group);
        groups.flush();
        log.info("Admin {} deleted group {} in {}", admin, id, group.getCs());
        events.publishEvent(new OverlayChanged(group.getCs()));
    }

    // --- members -------------------------------------------------------------------------------------------------

    /**
     * Adds or moves people to the group (atomic: all or none). A person gets an overlay row in the function when
     * they have none (they then appear in it). Moving someone into their AD default group clears the manual group.
     */
    @Transactional
    public MembersResult addMembers(long id, MembersRequest request, String admin) {
        guards.requireEditing();
        FunctionGroup group = requireGroup(id);
        if (!group.isActive()) {
            throw AdminEditException.conflict("GROUP_INACTIVE", "This group is not active.");
        }
        List<Long> ids = request == null || request.employeeIds() == null ? List.of() : request.employeeIds();
        if (ids.isEmpty() || ids.contains(null) || new HashSet<>(ids).size() != ids.size()) {
            throw AdminEditException.badRequest("EMPLOYEES", "List every person once.");
        }
        if (ids.size() > MAX_MEMBERS_PER_REQUEST) {
            throw AdminEditException.badRequest("TOO_MANY", "At most " + MAX_MEMBERS_PER_REQUEST
                    + " people at a time.");
        }
        Map<Long, String> names = new HashMap<>();
        groups.findByCsOrderBySortOrderAscNameAsc(group.getCs()).forEach(g -> names.put(g.getId(), g.getName()));
        List<Moved> moved = new ArrayList<>();
        List<EmployeeOverride> changed = new ArrayList<>();
        for (Long employeeId : ids) {
            Employee employee = guards.requireListedEmployee(employeeId);
            EmployeeOverride row = overlay.findByEmployeeIdAndCs(employeeId, group.getCs())
                    .orElseGet(() -> EmployeeOverride.builder().employeeId(employeeId).cs(group.getCs()).build());
            Long previous = row.getGroupId();
            Long target = manualGroupId(employee, group.getCs(), group);
            moved.add(new Moved(employeeId, previous, previous == null ? null : names.get(previous)));
            row.setGroupId(target);
            row.setGroupName(target == null ? null : group.getName());
            row.setUpdatedBy(admin);
            changed.add(row);
        }
        overlay.saveAllAndFlush(changed);
        log.info("Admin {} moved employees {} to group {} in {}", admin, ids, id, group.getCs());
        events.publishEvent(new OverlayChanged(group.getCs()));
        return new MembersResult(group.getId(), moved);
    }

    /**
     * Takes a person out of the group: back to their AD default group. 409 when the group is their AD default (they
     * belong here by Active Directory: move them to another group instead).
     */
    @Transactional
    public void removeMember(long id, long employeeId, String admin) {
        guards.requireEditing();
        FunctionGroup group = requireGroup(id);
        Employee employee = guards.requireEmployee(employeeId);
        EmployeeOverride row = overlay.findByEmployeeIdAndCs(employeeId, group.getCs()).orElse(null);
        boolean manual = row != null && group.getId().equals(row.getGroupId());
        if (isAdDefaultGroup(employee, group.getCs(), group)) {
            throw AdminEditException.conflict("AD_DEFAULT_GROUP", (employee.getDisplayName() == null ? "This person"
                    : employee.getDisplayName()) + " belongs here by Active Directory, move them to another group.");
        }
        if (!manual) {
            throw AdminEditException.notFound("NOT_A_MEMBER", "This person is not in this group.");
        }
        row.setGroupId(null);
        row.setGroupName(null);
        row.setUpdatedBy(admin);
        overlay.saveAndFlush(row);
        log.info("Admin {} took employee {} out of group {} in {}", admin, employeeId, id, group.getCs());
        events.publishEvent(new OverlayChanged(group.getCs()));
    }

    // --- used by the overlay edit, the importer, the seeder and the registrar -----------------------------------

    /** The group named so in the function, made (MANUAL, with the group rules) when there is none yet */
    @Transactional
    public FunctionGroup groupForEdit(String cs, String name, String admin) {
        FunctionGroup existing = groups.findByCsAndNameIgnoreCase(cs, name.trim()).orElse(null);
        if (existing != null) {
            return existing;
        }
        String kind = COUNTRY_CODES.contains(name.trim().toUpperCase(Locale.ROOT)) ? FunctionGroup.COUNTRY
                : FunctionGroup.TEAM;
        String checked = checkedName(cs, name, kind, null);
        FunctionGroup group = groups.saveAndFlush(FunctionGroup.builder().cs(cs).name(checked).kind(kind)
                .source(FunctionGroup.MANUAL).sortOrder(groups.maxSortOrder(cs) + 10).createdBy(admin)
                .updatedBy(admin).build());
        log.info("Admin {} created group {} ({}) in {} through an edit", admin, group.getId(), kind, cs);
        return group;
    }

    /** The group named so, made as it is (no rules: names that exist already, e.g. from the Excel import) */
    @Transactional
    public FunctionGroup resolveOrCreate(String cs, String name, String source, String by) {
        String trimmed = name.trim();
        return groups.findByCsAndNameIgnoreCase(cs, trimmed).orElseGet(() -> groups.saveAndFlush(FunctionGroup
                .builder().cs(cs).name(trimmed)
                .kind(COUNTRY_CODES.contains(trimmed.toUpperCase(Locale.ROOT)) ? FunctionGroup.COUNTRY : FunctionGroup.TEAM)
                .source(source).sortOrder(groups.maxSortOrder(cs) + 10).createdBy(by).updatedBy(by).build()));
    }

    /** The group id to store for the person: null when the group is their AD default group (no manual group) */
    public Long manualGroupId(Employee employee, String cs, FunctionGroup group) {
        return isAdDefaultGroup(employee, cs, group) ? null : group.getId();
    }

    private boolean isAdDefaultGroup(Employee employee, String cs, FunctionGroup group) {
        AdPlacement placement = placement(employee);
        return placement != null && cs.equals(placement.cs()) && placement.defaultGroup() != null
                && placement.defaultGroup().equalsIgnoreCase(group.getName());
    }

    private AdPlacement placement(Employee employee) {
        DbEmployeeDirectory db = dbDirectory.getIfAvailable();
        return db == null ? null : db.adPlacement(employee);
    }

    /** Registers the AD default groups (function, name) that have no group object yet as AUTO groups; never deletes */
    @Transactional
    public int registerAutoGroups(Collection<AdPlacement> placements) {
        Set<String> seen = new HashSet<>();
        int created = 0;
        for (AdPlacement p : placements) {
            if (p == null || p.cs() == null || p.defaultGroup() == null || p.defaultGroup().isBlank()
                    || !seen.add(p.cs() + "\n" + key(p.defaultGroup()))) {
                continue;
            }
            if (groups.findByCsAndNameIgnoreCase(p.cs(), p.defaultGroup().trim()).isEmpty()) {
                resolveOrCreate(p.cs(), p.defaultGroup(), FunctionGroup.AUTO, "ad-sync");
                created++;
            }
        }
        return created;
    }

    // --- checks --------------------------------------------------------------------------------------------------

    private FunctionGroup requireGroup(long id) {
        return groups.findById(id).orElseThrow(() -> AdminEditException.notFound("UNKNOWN_GROUP", "no group " + id));
    }

    private static void requireVersion(FunctionGroup group, Long version) {
        if (version == null || !version.equals(group.getVersion())) {
            throw AdminEditException.conflict("CHANGED", "Changed by someone else: reload the groups.");
        }
    }

    /** The name with the label rules, the reserved names of TEAM groups and the codes of COUNTRY groups */
    private String checkedName(String cs, String raw, String kind, Long self) {
        FieldRules.Errors errors = new FieldRules.Errors();
        String name = errors.apply(FieldRules.GROUP, raw);
        errors.throwIfAny();
        if (!FunctionGroup.TEAM.equals(kind) && !FunctionGroup.COUNTRY.equals(kind)) {
            errors.add("kind", "KIND", "A group is a team or a country.");
        } else if (FunctionGroup.COUNTRY.equals(kind)) {
            name = name.toUpperCase(Locale.ROOT);
            if (!COUNTRY_CODES.contains(name)) {
                errors.add("name", "COUNTRY_CODE", "A country group is named with its code: "
                        + String.join(", ", COUNTRY_CODES.stream().sorted().toList()) + ".");
            }
        } else {
            if (RESERVED_TEAM_NAMES.contains(key(name))) {
                errors.add("name", "RESERVED", "HEAD and Other are reserved names.");
            }
            FunctionGroup country = groups.findByCsAndNameIgnoreCase(cs, name).orElse(null);
            if (country != null && FunctionGroup.COUNTRY.equals(country.getKind())) {
                errors.add("name", "RESERVED", name + " is the country group of this function.");
            }
        }
        errors.throwIfAny();
        FunctionGroup same = groups.findByCsAndNameIgnoreCase(cs, name).orElse(null);
        if (same != null && !same.getId().equals(self)) {
            throw AdminEditException.conflict("GROUP_EXISTS", "There is already a group named " + same.getName()
                    + " in " + cs + ".");
        }
        return name;
    }

    /** Manual members (also hidden people) plus everyone whose AD default group it is */
    private int memberCount(FunctionGroup group) {
        int manual = overlay.findByGroupId(group.getId()).size();
        long byAd = directory.getAllEmployees().stream()
                .filter(e -> group.getCs().equals(e.getCs()) && e.getGroup() != null
                        && e.getGroup().equalsIgnoreCase(group.getName()))
                .count();
        return (int) Math.max(manual, byAd);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
